# kudkeys server

The keyboard's backend, at `https://keys.kudcrafts.com`. One container serves:

- **`/v1/stream`** — the voice WebSocket. It holds the provider keys, so the APK (public on
  GitHub) never contains one. The keyboard streams microphone audio and gets text back
  phrase by phrase.
- **`/api/*`** — accounts (Better Auth), the voice profile and usage. The contract the Android
  app builds against is [below](#api-for-the-app).
- **`/`** — the web app (`app/`, Vite + React): sign in, profile, usage, signed-in devices.

Layout follows `~/Projects/board`: root `package.json` with workspaces (`app`, `server`),
`prisma/` at the root, Hono on Bun, one Dockerfile at the root.

| Path | What |
|---|---|
| `server/index.ts` | Entry: wires Postgres, Better Auth, the Hono app and the WebSocket |
| `server/server.ts` | `Bun.serve`: `/v1/stream` upgrade + auth, everything else to Hono |
| `server/app.ts`, `server/routes/` | Hono: `/api/health`, `/api/auth/*`, `/api/me*`, the SPA |
| `server/auth.ts` | Better Auth (email + password, allowlist, bearer plugin) |
| `server/lib/store.ts` | Profile + usage rows (Prisma; memory for tests) |
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
(sign-up, `/api/me`, a stream, its usage row); CI runs it against the built image.

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
| `POST /api/auth/sign-in/email` | `{"email","password"}` | 200 `{user, token, redirect}`, and the session token in the **`set-auth-token`** response header. 401 `INVALID_EMAIL_OR_PASSWORD` |
| `POST /api/auth/sign-up/email` | `{"email","password","name"}` | Same as sign-in (signed in straight away). 403 `EMAIL_NOT_ALLOWED` outside `KUDKEYS_ALLOWED_EMAILS`; 422 `USER_ALREADY_EXISTS_USE_ANOTHER_EMAIL`; 400 `PASSWORD_TOO_SHORT` (< 10) |
| `GET /api/auth/get-session` | — | `{session, user}`, or `null` when the token is no longer valid |
| `POST /api/auth/sign-out` | `{}` | 200; this token stops working, `/v1/stream` included (other devices stay signed in) |
| `GET /api/auth/list-sessions` | — | Every signed-in device (`token`, `userAgent`, `ipAddress`, `createdAt`, `updatedAt`) |
| `POST /api/auth/revoke-session` | `{"token"}` | 200; signs that device out |

- Store the `set-auth-token` header value as-is (it is `<token>.<signature>`; the body's `token` is the unsigned half and also works) and send it back
  unchanged. Requests from the app carry no `Origin` header and need none.
- Sessions last **60 days** and roll forward (at most once a day) whenever the token is used —
  a `/v1/stream` upgrade counts — so a keyboard in daily use stays signed in.
- Send a recognisable `User-Agent`, e.g. `KudKeys/0.2 (Android 15; Pixel 8)`: the web app's
  Devices page names sessions from it ("Kud Keys keyboard on Android").
- A 401 from any `/api/*` call or the stream upgrade means: sign in again. A 403
  `not_allowed` means the email was removed from the allowlist.

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

Anything else → 401 before the upgrade. The log line says which kind was used
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
| `KUDKEYS_TOKENS` | optional | Legacy static stream tokens, comma-separated, ≥ 24 chars each |
| `KUDKEYS_PROVIDER` | `groq` | `groq` or `openai` |
| `GROQ_API_KEY` | — | Without it the server starts, and `/health` reports no transcription |
| `KUDKEYS_MODEL` | `whisper-large-v3` | For Groq; `whisper-large-v3-turbo` is cheaper and a little less accurate |
| `KUDKEYS_PAUSE_MS` | `800` | Pause that ends a phrase |
| `KUDKEYS_PARTIAL_MS` | `0` | > 0 turns on live partials (see above) |
| `KUDKEYS_GROQ_RPM` | `20` | Your Groq requests/minute limit |
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
rows with no text. `test/migration.test.ts` applies the migrations to PGlite.

CI: `.github/workflows/server.yaml` (typecheck, tests, SPA build; then the Docker image is
built, run against Postgres with migrations, and smoke-tested).

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

The keyboard's legacy token is `KUDKEYS_TOKENS` in Coolify; a copy lives in
`~/.config/kudkeys/token` on hammas-dev (mode 600). It keeps working alongside accounts.
