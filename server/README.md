# kudkeys server

The keyboard's voice backend. It holds the provider keys, so the APK (public on GitHub)
never contains one. The keyboard streams microphone audio over a WebSocket and gets text
back phrase by phrase.

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

```bash
cp .env.example .env          # set KUDKEYS_TOKENS and GROQ_API_KEY
bun install
bun --env-file=.env src/index.ts
curl localhost:8080/health    # {"ok":true,"transcription":"groq:whisper-large-v3"}
```

Try a real voice note, sent the way the keyboard will send it (100 ms frames, real time):

```bash
ffmpeg -i note.m4a -ac 1 -ar 16000 -sample_fmt s16 note.wav
KUDKEYS_TOKEN=… bun scripts/stream-wav.ts note.wav --keywords Fenno,TypeMap,FaceMap
```

It shows the text as it arrives, then each phrase with its confidence, the time to the first
words, and how long the last phrase takes after stop.

## Endpoints

| Path | What |
|---|---|
| `GET /health` | `{ok, transcription}`; `transcription` is `null` when the provider has no key |
| `GET /v1/stream` | WebSocket. `Authorization: Bearer <token>` required |

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

- **Transcript text is never logged or stored.** Logs carry the session id, audio seconds,
  number of finals, outcome and wall time only. `private: true` changes nothing on the
  server today, because it keeps nothing; it's accepted so later features (vocabulary,
  history) can honour it.
- Sessions stop at `KUDKEYS_MAX_SESSION_SECONDS` of audio (default 600) and after
  `KUDKEYS_IDLE_SECONDS` with no frames (default 30), ending gracefully with finals, so a
  stuck mic can't run up the bill.
- A final that hits 429 or a 5xx is retried once (honouring `retry-after`, up to 5 s).
  A refused key ends the session with a `provider` error.

## Config

| Env | Default | |
|---|---|---|
| `KUDKEYS_TOKENS` | required | Comma-separated, ≥ 24 chars each (`openssl rand -hex 32`) |
| `KUDKEYS_PROVIDER` | `groq` | `groq` or `openai` |
| `GROQ_API_KEY` | — | Without it the server starts, and `/health` reports no transcription |
| `KUDKEYS_MODEL` | `whisper-large-v3` | For Groq; `whisper-large-v3-turbo` is cheaper and a little less accurate |
| `KUDKEYS_PAUSE_MS` | `800` | Pause that ends a phrase |
| `KUDKEYS_PARTIAL_MS` | `0` | > 0 turns on live partials (see above) |
| `KUDKEYS_GROQ_RPM` | `20` | Your Groq requests/minute limit |
| `KUDKEYS_LANGUAGES` | `id,en` | Default when the keyboard sends none |
| `OPENAI_API_KEY` | — | Only for `KUDKEYS_PROVIDER=openai` |
| `KUDKEYS_DELAY`, `KUDKEYS_NOISE_REDUCTION` | unset | OpenAI only |
| `KUDKEYS_MAX_SESSION_SECONDS` | `600` | |
| `KUDKEYS_IDLE_SECONDS` | `30` | |
| `PORT` | `8080` | |

## Tests

`bun test` runs the stream end to end against a fake Groq API and a fake OpenAI realtime
server: phrase splitting, silence never sent, Whisper's made-up text dropped, 429 retry,
refused key, optional partials, resampling, the session cap, and a missing key.
