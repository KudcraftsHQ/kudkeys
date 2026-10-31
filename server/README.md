# kudkeys server

The keyboard's backend, at `https://keys.kudcrafts.com`. One container serves:

- **`/v1/stream`** — the voice WebSocket. It holds the provider keys, so the APK (public on
  GitHub) never contains one. The keyboard streams microphone audio and gets text back
  phrase by phrase.
- **`/api/*`** — accounts (Better Auth), the voice profile and usage, AI text actions, and
  settings/words sync and backups (v0.4). The contract the Android
  app builds against is [below](#api-for-the-app).
- **`/`** — the web app (`app/`, Vite + React): sign in, profile, usage, words, backups,
  signed-in devices.

Layout follows `~/Projects/board`: root `package.json` with workspaces (`app`, `server`),
`prisma/` at the root, Hono on Bun, one Dockerfile at the root.

| Path | What |
|---|---|
| `server/index.ts` | Entry: wires Postgres, Better Auth, the Hono app and the WebSocket |
| `server/server.ts` | `Bun.serve`: `/v1/stream` upgrade + auth, everything else to Hono |
| `server/app.ts`, `server/routes/` | Hono: `/api/health`, `/api/auth/*`, `/api/me*`, the SPA |
| `server/auth.ts` | Better Auth (email + password, allowlist, bearer plugin) |
| `server/lib/store.ts` | Profile + usage rows (Prisma; memory for tests) |
| `server/routes/sync.ts`, `server/sync/` | Settings and words sync, backups (raw SQL via `server/lib/sql.ts`: Prisma in production, PGlite in tests) |
| `server/voice/` | The voice pipeline: protocol, session, segmenter, providers |
| `app/` | The web app, built into `server/public` |
| `prisma/` | Schema and migrations |

**Default provider: Groq Whisper (`whisper-large-v3`).** OpenAI realtime
(`gpt-live-transcribe`) is kept as an alternative (`KUDKEYS_PROVIDER=openai`). Providers sit
behind `src/providers/types.ts`, so adding another doesn't change the keyboard.

## How Groq works here

Groq has no streaming endpoint; it transcribes finished files. So the server:

1. converts the mic stream to 16 kHz,
2. cuts it into **phrases at pauses** (`KUDKEYS_PAUSE_MS`, default 800 ms; also at 25 s, or when
   the keyboard sends `commit`/`stop`), with an energy detector in `src/segmenter.ts`,
3. trims the silence and sends each phrase to Groq as a small WAV,
4. returns each phrase as a `final`, with a 0–1 `confidence` from Whisper's `avg_logprob`.

Text therefore appears **per phrase** (right after you pause), not word by word.

- **Silence never reaches Groq.** Whisper invents text on silence ("Terima kasih telah
  menonton"); the server only sends audio with speech in it, and drops segments Whisper itself
  marks as probably-not-speech (`no_speech_prob`).
- **Live partials are optional.** `KUDKEYS_PARTIAL_MS=1500` re-sends the phrase in progress so
  words show while you talk. Off by default: Groq's base plan allows 20 requests/min and
  7,200 audio-seconds/hour and bills each request as at least 10 s, so partials only use
  spare budget (`KUDKEYS_GROQ_RPM`, keeping 6/min free for finals). Raise your Groq limits
  before turning them on.
- **Languages.** Whisper takes one language. When the keyboard sends `id` and `en` (the
  default), none is passed and Whisper detects per phrase; a single language is passed through.
- **Keywords** (names like Fenno, TypeMap) go into Whisper's `prompt`, which nudges spelling.

## Run

From the repo root (a dev Postgres, never production):

```bash
cp .env.example .env          # DATABASE_URL(_POOLING), BETTER_AUTH_SECRET, KUDKEYS_ALLOWED_EMAILS, GROQ_API_KEY
bun install
bunx prisma migrate dev       # apply migrations to the dev database
bun run dev                   # server on :8080 + Vite on :5181 (proxies /api)
curl localhost:8080/api/health   # {"ok":true,"transcription":"groq:whisper-large-v3"}
```

`bun run typecheck`, `bun run test`, `bun run build` (SPA into `server/public`).
`bun server/scripts/smoke.ts <base> <allowed email>` exercises a running server end to end
(sign-up, `/api/me`, text fix, settings and words sync, a backup round trip, a stream, its
usage row); CI runs it against the built image.

Try a real voice note, sent the way the keyboard will send it (100 ms frames, real time):

```bash
ffmpeg -i note.m4a -ac 1 -ar 16000 -sample_fmt s16 note.wav
KUDKEYS_TOKEN=… bun server/scripts/stream-wav.ts note.wav --keywords Fenno,TypeMap,FaceMap
```

`KUDKEYS_TOKEN` is a static token or an account's session token. It shows the text as it arrives, then each phrase with its confidence, the time to the first
words, and how long the last phrase takes after stop.

## API for the app

The contract the Android app builds against. JSON in and out; errors are
`{"error": "…"}` with the status below.

### Signing in

Better Auth, mounted at `/api/auth/*`; its errors are `{"code","message"}`. The app keeps no cookies: it sends the session token as
`Authorization: Bearer <token>` on every call, including the `/v1/stream` upgrade.

| Call | Body | Answer |
|---|---|---|
| `POST /api/auth/sign-in/email` | `{"email","password"}` | 200 `{user, token, redirect}`, and the session token in the **`set-auth-token`** response header. 401 `INVALID_EMAIL_OR_PASSWORD`; 429 after 5 tries a minute from one IP |
| `POST /api/auth/sign-up/email` | `{"email","password","name"}` | Same as sign-in (signed in straight away). 403 `EMAIL_NOT_ALLOWED` outside `KUDKEYS_ALLOWED_EMAILS`; 400 `EMAIL_PASSWORD_SIGN_UP_DISABLED` when `KUDKEYS_SIGNUP_OPEN=false`; 422 `USER_ALREADY_EXISTS_USE_ANOTHER_EMAIL`; 400 `PASSWORD_TOO_SHORT` (< 8) |
| `GET /api/auth/get-session` | — | `{session, user}`, or `null` when the token is no longer valid |
| `POST /api/auth/sign-out` | `{}` | 200; this token stops working, `/v1/stream` included (other devices stay signed in). The app sends `Origin: <server base>` here so Better Auth's origin check passes |
| `GET /api/auth/list-sessions` | — | Every signed-in device (`token`, `userAgent`, `ipAddress`, `createdAt`, `updatedAt`) |
| `POST /api/auth/revoke-session` | `{"token"}` | 200; signs that device out |

- Store the `set-auth-token` header value as-is (it is `<token>.<signature>`; the body's `token` is the unsigned half and also works) and send it back
  unchanged. Requests from the app carry no `Origin` header and need none.
- Sessions last **60 days** and roll forward (at most once a day) whenever the token is used —
  a `/v1/stream` upgrade counts — so a keyboard in daily use stays signed in.
- Send a recognisable `User-Agent`, e.g. `KudKeys/0.2 (Android 15; Pixel 8)`: the web app's
  Devices page names sessions from it ("Kud Keys keyboard on Android").
- A 401 from any `/api/*` call or the stream upgrade means: sign in again. A 403
  `not_allowed` means the email was removed from the allowlist. A **503**
  `{"error":"unavailable","reason":"session_lookup_failed"}` means the session could not be
  checked (Postgres or pgbouncer down): keep the token and try again later.
- Rate limits (Better Auth, in memory) key on the client IP from `cf-connecting-ip`, or the
  socket address when that header is missing; `X-Forwarded-For` is ignored, since behind
  Cloudflare its first entry is whatever the client sent. Sign-in: 5 a minute per IP.

### Profile

`GET /api/me` → 200

```json
{"id":"1Bqy…","email":"hammas.hamzah@gmail.com","name":"Hammas","image":null,
 "voiceKeywords":["Fenno","TypeMap"],"languages":["id","en"]}
```

`PATCH /api/me` with any subset of `{name, image, voiceKeywords, languages}` → 200 and the
whole profile, as above.

| Field | Rule |
|---|---|
| `name` | 1–100 characters |
| `image` | a URL, or `null` |
| `voiceKeywords` | ≤ 100 strings, each 1–100 characters, no `<`, `>` or line breaks. Trimmed; the same word in another case is dropped (first spelling wins). Replaces the whole list |
| `languages` | 1–10 ISO 639-1 codes (`"id"`, `"en"`). Default `["id","en"]` |

Anything else (unknown fields, bad values) → 400
`{"error":"invalid","issues":[{"path":"voiceKeywords","message":"…"}]}`.

### Usage

`GET /api/me/usage` → 200

```json
{"week":  {"since":"2026-10-25T17:00:00.000Z","sessions":12,"audioMinutes":8.4},
 "month": {"since":"2026-09-30T17:00:00.000Z","sessions":40,"audioMinutes":31.2},
 "sessions":[{"id":"cmvw…","session":"f87ce63f","startedAt":"…","endedAt":"…",
              "audioSeconds":4.0,"finals":2,"outcome":"stopped","model":"whisper-large-v3"}]}
```

Weeks start Monday 00:00 and months on the 1st, Jakarta time. `sessions` is the latest 50,
newest first. `outcome` is `stopped`, `idle`, `limit`, `cancelled`, `client closed` or
`error <code>`. `session` is the id the stream sent in `ready`.

### Text: tidy, fix, instruct

The keyboard's AI text actions (v0.3), on Groq chat completions in JSON mode with the same
`GROQ_API_KEY` as voice. Models: **fix** and **instruct** on `openai/gpt-oss-120b`, **tidy** on
`qwen/qwen3.8-27b` (picked with `scripts/eval-text.ts`: gpt-oss-120b fixes typos and follows
instructions best; Qwen tidies dictation best, fixing names and asking useful questions, at a
third of the latency). `KUDKEYS_TEXT_MODEL` sets one model for all three; `KUDKEYS_TIDY_MODEL`
then overrides tidy. All three
need a **session token** (`Authorization: Bearer <token>`); a legacy `KUDKEYS_TOKENS` entry is
refused with 401. Every prompt carries the profile's `voiceKeywords` (spelling of names) and
`languages` (the request's `languages`, when sent, win).

| Call | Body | Answer |
|---|---|---|
| `POST /api/text/tidy` | `{"text": ≤20000 chars, "segments"?: [{"text", "confidence"?: 0–1}], "languages"?, "private"?}` | `{"text", "questions": [{"id","original","start","end","options"}]}` |
| `POST /api/text/fix` | `{"text": ≤4000 chars, "languages"?, "private"?}` | `{"text", "changed"}` |
| `POST /api/text/instruct` | `{"instruction": 1–2000 chars, "text": ≤20000 chars (may be ""), "languages"?, "private"?}` | `{"text"}` |

- **tidy** turns raw dictation into clean text: punctuation, paragraphs, a numbered or bulleted
  list only where the speaker clearly enumerated ("pertama… kedua…"). Slang stays slang
  (nggak, gue, udah, banget, belom); hesitations (eh, emm) and stutters go; only obvious
  mis-hearings change, above all keywords ("Feno" → "Fenno"). Segments with `confidence` < 0.7
  are passed as "unsure", and the model may ask about words in them. **`questions`** are
  checked here, not trusted: `text.slice(start, end) === original` on word boundaries, always;
  a question whose word isn't in the text, or has no option, is dropped. At most 5, in text
  order, never overlapping; `options` are 1–3 alternatives to `original` (offer "keep as is"
  yourself); an option equal to `original` apart from case or punctuation is dropped. `text` is
  trimmed and the offsets index it. A transcript over ~7,500 chars is tidied in chunks cut at
  paragraph breaks, sentence ends or spaces, in parallel, and joined in order (offsets adjusted).
  Empty `text` → `{"text":"","questions":[]}` with no model call.
- **fix** corrects typos, punctuation and capitalisation only; meaning, language mix, slang and
  chat abbreviations (yg, bgt, btw) stay. `changed` is `false` exactly when the returned text
  equals the input (compared here, not taken from the model). A "fix" whose edit distance is more
  than 40% of the input (at least 8 characters) rewrote the text instead of fixing it (a pasted
  "ignore previous instructions…", a model that answered) and is refused with 422.
- **instruct** applies `instruction` to `text` and returns the whole result. With empty `text`
  it writes from the instruction ("write a polite reply saying I'll be 10 minutes late"). It
  answers in the language of the text, or with no text in the instruction's language.

Errors: `400 {"error":"invalid","issues":[…]}` bad input · `401` no or invalid session (or a
legacy token) · `429 {"error":"rate_limited","retryAfter"}` with a `Retry-After` header (seconds):
over **30 calls a minute** per user (`KUDKEYS_TEXT_RPM`), the token budget below, or Groq's own
limit · `413 {"error":"too_long"}`: the request can never fit the model's tokens per minute (or
Groq said 413); the app keeps the text as it is · `422 {"error":"changed_too_much"}` (fix only, see
above) · `503 {"error":"unavailable","reason"}`: Groq down or refused, a timeout (15 s fix, 45 s
tidy and instruct), invalid JSON from the model twice, or no `GROQ_API_KEY`.

Groq's free tier allows **8,000 tokens a minute per model** (tidy and fix/instruct run on
different models, so each has its own budget) and counts what a request *asks* for: prompt +
`max_completion_tokens`. So `max_completion_tokens` follows the input (≈ input tokens × 1.25 +
768; instruct at least 1,536), tokens are estimated as chars ÷ 3, and the server keeps its own
per-model count (`KUDKEYS_TEXT_TPM`). A request that can never fit answers 413 at once; one that
fits once earlier calls age out answers 429 with that wait in `Retry-After`, without calling Groq.
The retry on invalid JSON only happens when the budget has room. In practice a tidy of up to
~7,000 chars (about 6 minutes of speech) fits on the free tier; longer ones need a paid plan (raise
`KUDKEYS_TEXT_TPM`).

**Privacy:** request and response text are never logged or stored. Each call that reaches the
model writes one `text_usage` row: `kind`, `charsIn`, `charsOut`, `ms`, `model`, `ok`. With
`private: true` the character counts are `null`, so the row holds only the kind and timing.

### Sync: settings and words (v0.4)

All of these need a **session token**; a legacy `KUDKEYS_TOKENS` entry gets 401. Times are
ISO 8601 with an offset (`2026-10-31T09:12:00.000Z`) or epoch milliseconds; answers are always
ISO in UTC. A device time in the future is treated as the server's now (so a phone with a wrong
clock can't win every merge forever). Validation errors are `400 {"error":"invalid","issues":[…]}`
as for `/api/me`; a body over its limit is `413 {"error":"too_large","maxBytes"}`.

| Call | Body | Answer |
|---|---|---|
| `GET /api/sync/settings` | — | `{prefs, updatedAt}`, or 404 `{"error":"not_found"}` before the first sync |
| `PUT /api/sync/settings` | `{prefs: {…}, updatedAt, deviceId: 1–200 chars}` | `{prefs, updatedAt}`: the merged result. Write it back to the phone |
| `GET /api/sync/words?since=<time>` | — | `{words: [{word, locale, count, source, deleted, updatedAt}], now}` |
| `POST /api/sync/words` | `{words: [{word, locale, count, source, deleted, updatedAt}]}` (≤ 5,000) | `{accepted, changed, now}` |
| `POST /api/sync/words/import` | `{locale, source: "swiftkey" \| "manual", words: [string]}` (≤ 50,000) | `{added, skipped}` |
| `GET /api/sync/status` | — | Totals for the web app (below) |

**Settings** merge **last writer wins per key**, by the `updatedAt` the device sends (its time of
the change). Keys are leaf paths: nested objects are walked, so a flat `{"theme":"dark"}` and
HeliBoard's grouped `{"boolean":{"vibrate_on":true}}` both merge key by key; arrays (string sets)
and other non-objects are values. Rules:
- a key whose stored time is newer than `updatedAt` keeps its value; otherwise the sent value
  wins (a tie goes to the request, so a retried PUT is harmless);
- **a key sent as `null` is removed** and stays removed until a newer write sets it;
- **a key left out is kept**: sending part of the settings never deletes the rest;
- **an empty object is an empty group, never a value**: HeliBoard's `{"float":{}}` adds and removes
  nothing, so a key that later appears in that group merges like any other (send `null` per key to
  remove one);
- a path that is a prefix of another (a value where a group now is, or the reverse) is replaced by
  the newer write;
- the answer's `updatedAt` is the newest time of any key;
- `prefs` (as stored after merging) is at most **256 KB** of JSON; the request body at most 320 KB.

The phone leaves out anything secret or per-device (tokens, server URL, voice draft, account and
sync bookkeeping, keyboard height/padding scales) before sending; the server stores what it gets.

**Words** are rows keyed by `(word, locale)`:

| Field | Rule |
|---|---|
| `word` | 1–48 characters (HeliBoard's maximum), **one word: no spaces**, control characters or line breaks (the keyboard's dictionaries hold single words). Case-sensitive: `Fenno` and `fenno` are two words |
| `locale` | **Stored as the language**: the lowercase ISO 639 code, no region or script (`en`, `id`, `pt`). Any tag is accepted and normalised: `en_US`, `en-GB`, `EN` → `en`; Java's `in` and `id_ID` → `id` (also `iw` → `he`, `ji` → `yi`). The phone keys words the same way (one synced dictionary per language), so a tombstone from the phone always meets the row a web add or an import made. Answers always carry the canonical form |
| `count` | integer 0–1,000,000,000 |
| `source` | `typed` (learned on the phone), `swiftkey` (imported), `manual` (added on the web or in the app) |
| `deleted` | `true` = a tombstone: the word was removed |
| `updatedAt` | the device's time of this change |

Merge rule (`POST`, applied in Postgres in one statement per 5,000 rows):
- **union** by `(word, locale)`;
- **`count = max`** of stored and sent (also for tombstones, so a word re-added later keeps its count);
- **`deleted` follows the newer `updatedAt`**: a delete newer than the stored add wins; an older
  add doesn't undo a newer delete; a newer add revives a tombstone. **On a tie the delete wins.**
  `updatedAt` becomes the newer of the two;
- `source` stays the first one seen, except that an add reviving a tombstone brings its own;
- the same word twice in one call is merged by the same rule first.

`accepted` is the number of distinct `(word, locale)` merged, `changed` how many of them actually
changed. Sending a row that changes nothing (same count or lower, not newer) is a no-op and doesn't
show up in anyone's `?since=`.

**Pulling:** `GET /api/sync/words` without `since` returns every row, **tombstones included**
(delete those locally; write the others into the synced dictionary). With `since`, only rows
**changed on the server** at or after that time (server clock, not device `updatedAt`). Pass the
`now` of your previous `GET` or `POST` as the next `since`. `now` is the server's time minus
60 s, so a write that was still committing is read again next time; the merge is idempotent, so
seeing a row twice is harmless. No pagination: 17k words is about 1.5 MB of JSON.

**Import** adds words that aren't there yet with `count: 1`, `deleted: false` and the given
`source`. Each word is trimmed; duplicates in the call, words already stored (**tombstones
included**: an import never revives a removed word), and strings that aren't valid words are
skipped, not refused. `added + skipped` = the number of strings sent. Body at most 8 MB.

`GET /api/sync/status` (the web app):

```json
{"settings": {"updatedAt":"…","syncedAt":"…","deviceId":"…","keys":212} | null,
 "words": {"total":17164,"removed":3,"bySource":{"swiftkey":17000,"typed":160,"manual":4},
           "byLocale":{"id":12000,"en":5164},"lastChangedAt":"…"},
 "backups": {"count":12,"totalBytes":48200000,"latestAt":"…"}}
```

`syncedAt` is the server time of the last settings PUT, `keys` the number of settings stored;
`words.total` and its breakdowns count live words, `removed` the tombstones.

`GET /api/words?q=&locale=&source=&removed=1&offset=0&limit=50` (the web app's Words page):
`{total, offset, limit, words: [...]}`, most used first. `q` matches anywhere in the word, case
insensitive (`%` and `_` are literal); `removed=1` lists tombstones instead of live words;
`limit` ≤ 200; `locale` takes any form (`en_US` finds `en`).

`POST /api/words` `{word, locale, deleted, source?}` (the web app's add, remove and restore) →
`{changed, locale}`. The server stamps the write with **its own clock**, never the browser's, so a
browser whose clock is behind can't lose to the row it is removing. `source` defaults to `manual`
(a restore passes the word's own). `changed: false` means a newer change already holds the word
(or it was already in that state); the page says so instead of claiming it worked.

### Backups (v0.4)

The phone's HeliBoard backup zip (learned-word dictionaries, user dictionaries, prefs JSON minus
secrets), stored as one gzip-compressed blob in Postgres with its sha256. Session token only.

| Call | Request | Answer |
|---|---|---|
| `GET /api/backups` | — | `[{id, createdAt, deviceName, kind, sizeBytes}]`, newest first |
| `POST /api/backups` | the zip as the body, `Content-Type: application/zip` (or `application/octet-stream`); headers `X-Device-Id` (required, ≤ 200), `X-Device-Name` (optional, ≤ 200, ASCII), `X-Backup-Kind: auto \| manual` (required), `X-Backup-Sha256` (optional, hex: the zip's sha256; the phone always sends it) | `200 {id}`; `400 {"error":"checksum_mismatch"}` when the upload doesn't hash to `X-Backup-Sha256` (a truncated or corrupted upload is never stored) |
| `GET /api/backups/:id` | — | the zip, byte for byte: `Content-Type: application/zip`, `Content-Length`, `Content-Disposition: attachment`, **`X-Backup-Sha256`** (hex; check it before restoring) and the same as `ETag` |
| `DELETE /api/backups/:id` | — | `200 {"ok":true}` |

- At most **20 MB** per zip: a larger `Content-Length` is refused at once, and an upload that
  passes 20 MB while streaming is cut off; both `413 {"error":"too_large","maxBytes"}`. A body
  that isn't a zip (doesn't start with `PK`) or is empty → 400; another content type → 415.
- The upload is hashed and compressed as it streams in; only the compressed copy is held.
- An unknown id, or another user's, → 404.
- **Retention** per user, applied whenever a backup is added:
  - **automatic** backups: of the **14 most recent days** (Jakarta time) that have one, the newest
    of each day is kept; likewise the newest of each of the **8 most recent weeks**
    (Monday–Sunday) that have one. A backup can count for both. This is restic's
    `--keep-daily 14 --keep-weekly 8`. Days without backups don't count, so a phone that was off
    for a month never loses its last ones;
  - **manual** backups ("Back up now") are kept apart: the **newest 10**, whatever their day, so
    the automatic backup that runs later the same day never replaces the one taken before an
    experiment;
  - the newest **full** automatic backup (at least half the size of the biggest automatic one) is
    always kept, and an automatic backup **less than half the size of the previous one** (an
    emptied keyboard after a reinstall) prunes nothing. A run of near-empty backups can never
    push out the last good one; delete it by hand once it is no longer wanted.

**Privacy:** words, settings values and backup contents are never logged. These routes log counts
and sizes only (`words import: 120 sent, 118 added`, `backup stored: 812345 bytes …`), and an
error in them is logged by its name and Postgres code, never its message (a constraint message
can quote the row).

### Other endpoints

| Path | What |
|---|---|
| `GET /api/health` (alias `GET /health`) | `{ok, transcription}`, no database call; `transcription` is `null` when the provider has no key |
| `GET /v1/stream` | WebSocket, below |

## Stream auth

`/v1/stream` needs `Authorization: Bearer <x>` on the upgrade request, where `x` is either:

- an account's **session token** (the `set-auth-token` value), for an email still in
  `KUDKEYS_ALLOWED_EMAILS`. The profile then shapes the session:
  - its `voiceKeywords` are added to the keywords `start` sent (`start`'s first, then the
    profile's, duplicates dropped, at most 100) and go into Whisper's prompt;
  - when `start` sends no `languages`, the profile's are used;
  - a usage row is written when the session ends (below).
- a legacy static **`KUDKEYS_TOKENS`** entry, as before. No profile, no usage row.

Anything else → 401 before the upgrade; 503 (JSON, as above) when the session lookup itself
failed. The log line says which kind was used
(`auth=user` / `auth=token`), never the token or the account.

## Stream protocol

Keyboard → server:

| Frame | Meaning |
|---|---|
| `{"type":"start","sampleRate":16000,"languages":["id","en"],"keywords":["Fenno"],"prompt":"…","private":false}` | Begin. `sampleRate` is 16000, 24000 or 48000. |
| binary | PCM16 little-endian mono at `sampleRate`, ~100 ms per frame |
| `{"type":"commit"}` | End the current phrase now. Optional; the server also cuts at pauses. |
| `{"type":"stop"}` | Finish: the server sends the last finals, then `done`, then closes (1000) |
| `{"type":"cancel"}` | Drop everything and close |

Server → keyboard:

| Frame | Meaning |
|---|---|
| `{"type":"ready","session":"ab12cd34","model":"whisper-large-v3"}` | Send audio (audio sent earlier is queued, not lost) |
| `{"type":"partial","segment":0,"text":"halo aku"}` | Phrase 0 so far; **replaces** the previous partial. Only with partials on (or OpenAI). |
| `{"type":"final","segment":0,"text":"Halo, aku otw.","confidence":0.91}` | Phrase 0, settled; replaces its partial. `confidence` when the provider gives one. |
| `{"type":"done"}` | Every final is in |
| `{"type":"error","code":"provider","message":"…"}` | `bad_request`, `not_started`, `provider`, `limit`, `internal` |

Phrases are numbered in speaking order. A number can be skipped when a phrase turned out to
be silence. Finals can arrive out of order, so place them by `segment`.

Close codes: 1000 normal, 1008 the keyboard broke the protocol, 1011 provider or server failure.

## Guarantees

- **Transcript text is never logged or stored.** Logs carry the session id, auth kind, audio
  seconds, number of finals, outcome and wall time only.
- **Usage rows are numbers only.** For a session opened with an account, one `voice_session`
  row records: session id, start and end time, audio seconds, number of finals, outcome and
  model. The table has no column that could hold text. The row is written for
  `private: true` sessions too, because it says nothing about what was said; `private`
  otherwise changes nothing on the server today. It's accepted so later features
  (learned vocabulary, history) can honour it.
- Sessions stop at `KUDKEYS_MAX_SESSION_SECONDS` of audio (default 600) and after
  `KUDKEYS_IDLE_SECONDS` with no frames (default 30), ending gracefully with finals, so a
  stuck mic can't run up the bill.
- A final that hits 429 or a 5xx is retried once (honouring `retry-after`, up to 5 s).
  A refused key ends the session with a `provider` error.

## Config

| Env | Default | |
|---|---|---|
| `DATABASE_URL` | required | Direct Postgres URL; migrations (`prisma migrate deploy` at container start) |
| `DATABASE_URL_POOLING` | `DATABASE_URL` | Runtime, through pgbouncer, with `?pgbouncer=true` |
| `BETTER_AUTH_SECRET` | required | `openssl rand -base64 32`; the server refuses to start without it |
| `BETTER_AUTH_URL` | `http://localhost:8080` | The origin browsers use (`https://keys.kudcrafts.com`) |
| `KUDKEYS_ORIGIN` | — | Extra trusted origins, comma-separated (dev) |
| `KUDKEYS_ALLOWED_EMAILS` | empty = nobody | Who may sign up and use an account; re-checked on every request |
| `KUDKEYS_SIGNUP_OPEN` | `true` | `false` refuses every sign-up, allowlisted or not; existing accounts still sign in |
| `KUDKEYS_TOKENS` | optional | Legacy static stream tokens, comma-separated, ≥ 24 chars each |
| `KUDKEYS_PROVIDER` | `groq` | `groq` or `openai` |
| `GROQ_API_KEY` | — | Without it the server starts, and `/health` reports no transcription |
| `KUDKEYS_MODEL` | `whisper-large-v3` | For Groq; `whisper-large-v3-turbo` is cheaper and a little less accurate |
| `KUDKEYS_PAUSE_MS` | `800` | Pause that ends a phrase |
| `KUDKEYS_PARTIAL_MS` | `0` | > 0 turns on live partials (see above) |
| `KUDKEYS_GROQ_RPM` | `20` | Your Groq requests/minute limit |
| `KUDKEYS_TEXT_MODEL` | `openai/gpt-oss-120b` for fix and instruct, `qwen/qwen3.8-27b` for tidy | Groq chat model for `/api/text/*`; when set, used for all three. Chosen by `scripts/eval-text.ts` (see Tests) |
| `KUDKEYS_TIDY_MODEL` | `KUDKEYS_TEXT_MODEL` | Overrides the model for tidy only |
| `KUDKEYS_TEXT_RPM` | `30` | Text calls per user per minute |
| `KUDKEYS_TEXT_TPM` | `8000` | Groq tokens per minute per text model (Groq's free tier); `0` = not counted |
| `GROQ_CHAT_URL` | Groq's | Chat completions URL (tests and CI point it at a fake) |
| `KUDKEYS_LANGUAGES` | `id,en` | Default when neither `start` nor the profile names any (legacy tokens) |
| `OPENAI_API_KEY` | — | Only for `KUDKEYS_PROVIDER=openai` |
| `KUDKEYS_DELAY`, `KUDKEYS_NOISE_REDUCTION` | unset | OpenAI only |
| `KUDKEYS_MAX_SESSION_SECONDS` | `600` | |
| `KUDKEYS_IDLE_SECONDS` | `30` | |
| `PORT` | `8080` | |

## Tests

`bun run test` (from the root) runs the stream end to end against a fake Groq API and a fake
OpenAI realtime server: phrase splitting, silence never sent, Whisper's made-up text dropped,
429 retry, refused key, optional partials, resampling, the session cap, and a missing key.
`test/accounts.test.ts` runs real Better Auth (memory adapter) through the whole server:
allowlisted sign-up, bearer sign-in, `/api/me` validation, stream auth (session token, legacy
token, none, revoked, removed from the allowlist), keyword merge into the prompt, and usage
rows with no text. `test/text.test.ts` covers `/api/text/*` against a fake Groq chat server:
session-only auth, validation, the profile in the prompt, question offsets checked in code,
the retry on invalid JSON, 429/503 mapping, the per-user limit and usage rows without text.
`test/sync.test.ts` runs the sync and backup API against the real migrations on PGlite (made-up
words only): session-only auth, the size limits, the merge rules (max count, tombstone ordering,
ties, clamped future times), `?since=`, import dedupe, per-key settings merge, the backup round
trip with its sha256, retention pruning, and that no word, setting or backup byte reaches the
log even when the database errors.
`test/auth-hardening.test.ts` covers 503 (not 401) when the database fails
during a session lookup, the sign-in rate limit by `cf-connecting-ip` / socket address with a
spoofed `X-Forwarded-For`, and `KUDKEYS_SIGNUP_OPEN=false`. `test/migration.test.ts` applies
the migrations to PGlite.

Picking the text model: `GROQ_API_KEY=… bun server/scripts/eval-text.ts out.md [model …]` runs
the fixtures in `test/fixtures/text/` against real Groq models (a few cents) and writes every
output, latency and check result side by side. Never in CI.

CI: `.github/workflows/server.yaml` (typecheck, tests, SPA build; then the Docker image is
built, run against Postgres with migrations, and smoke-tested with its runtime queries going
through pgbouncer in transaction mode with `?pgbouncer=true`, as in production).

## Deploy

Coolify on vienna-1: project **Kud Keys**, app `kudkeys-server` (`ktmqsbqbalqnilyka83vx4rf`),
domain `http://keys.kudcrafts.com` (Cloudflare proxies and terminates HTTPS — an `https://`
domain in Coolify causes a redirect loop).

### Changes to make in Coolify before the first deploy of this version

The Dockerfile moved from `server/` to the repo root. In the app's settings:

| Setting | Was | Set to |
|---|---|---|
| Base Directory | `/server` | `/` |
| Dockerfile Location | `/Dockerfile` | `/Dockerfile` (unchanged — now resolves to the root one) |
| Ports Exposes | `8080` | `8080` (unchanged) |
| Healthcheck path | `/health` | `/api/health` (`/health` still answers, so either works) |
| Branch | — | whichever branch is deployed (`v0.2` until it's merged) |

Env (already set): `DATABASE_URL`, `DATABASE_URL_POOLING` (with `?pgbouncer=true`),
`BETTER_AUTH_SECRET`, `BETTER_AUTH_URL=https://keys.kudcrafts.com`, `KUDKEYS_ALLOWED_EMAILS`,
`GROQ_API_KEY`, `KUDKEYS_TOKENS`. All runtime, none build-time. Migrations run at container
start (`prisma migrate deploy`), so the first deploy creates the tables.

```bash
coolify deploy uuid ktmqsbqbalqnilyka83vx4rf
curl https://keys.kudcrafts.com/api/health
```

### Closing sign-up after the first deploy

Sign-up has no email verification: until an allowlisted email has an account, anyone who
knows it could register it first. Right after the first deploy:

1. Hammas creates his account (web app or the keyboard's Account screen).
2. Check it is his: sign in on the web app and see his name on the profile page.
3. Set `KUDKEYS_SIGNUP_OPEN=false` in Coolify and redeploy. Sign-up is then refused for
   everyone; existing accounts keep signing in. Set it back to `true` only while adding
   another allowlisted person, and close it again once they have signed up.

The keyboard's legacy token is `KUDKEYS_TOKENS` in Coolify; a copy lives in
`~/.config/kudkeys/token` on hammas-dev (mode 600). It keeps working alongside accounts.
