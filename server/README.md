# kudkeys server

The keyboard's voice backend. It holds the provider keys, so the APK (public on GitHub)
never contains one. The keyboard streams microphone audio over a WebSocket and gets the
words back as they are recognised.

Today it proxies **OpenAI `gpt-live-transcribe`** only. Providers sit behind
`src/providers/types.ts`, so adding another one doesn't change the keyboard.

## Run

```bash
cp .env.example .env          # set KUDKEYS_TOKENS and OPENAI_API_KEY
bun install
bun --env-file=.env src/index.ts
curl localhost:8080/health    # {"ok":true,"transcription":"gpt-live-transcribe"}
```

Try it with a real voice note, exactly as the keyboard will send it:

```bash
ffmpeg -i note.m4a -ac 1 -ar 16000 -sample_fmt s16 note.wav
KUDKEYS_TOKEN=… bun scripts/stream-wav.ts note.wav --keywords Fenno,TypeMap,FaceMap
```

It prints words as they stream, the final text, time to the first words, and how long the
last words take after stop.

## Endpoints

| Path | What |
|---|---|
| `GET /health` | `{ok, transcription}`; `transcription` is `null` when no OpenAI key is set |
| `GET /v1/stream` | WebSocket. `Authorization: Bearer <token>` required |

## Stream protocol

Keyboard → server:

| Frame | Meaning |
|---|---|
| `{"type":"start","sampleRate":16000,"languages":["id","en"],"keywords":["Fenno"],"prompt":"…","private":false}` | Begin. `sampleRate` is 16000, 24000 or 48000. `languages` defaults to `id,en`. |
| binary | PCM16 little-endian mono at `sampleRate`, ~100 ms per frame |
| `{"type":"commit"}` | End the current segment (e.g. the keyboard detected a pause). Optional. |
| `{"type":"stop"}` | Finish: the server sends the last finals, then `done`, then closes (1000) |
| `{"type":"cancel"}` | Drop everything and close |

Server → keyboard:

| Frame | Meaning |
|---|---|
| `{"type":"ready","session":"ab12cd34","model":"gpt-live-transcribe"}` | Start sending audio (audio sent earlier is queued, not lost) |
| `{"type":"delta","segment":0,"text":"halo "}` | Append to segment 0 |
| `{"type":"final","segment":0,"text":"Halo, aku otw."}` | Segment 0, settled. Replace its deltas with this. |
| `{"type":"done"}` | Every final is in |
| `{"type":"error","code":"provider","message":"…"}` | `bad_request`, `not_started`, `provider`, `limit`, `internal` |

Close codes: 1000 normal, 1008 the keyboard broke the protocol, 1011 provider or server failure.

## Guarantees

- **Transcript text is never logged or stored.** Logs carry the session id, audio seconds,
  number of finals, outcome and wall time only. `private: true` changes nothing on the
  server today, because it keeps nothing; it's accepted so later features (vocabulary,
  history) can honour it.
- Sessions stop at `KUDKEYS_MAX_SESSION_SECONDS` of audio (default 600) and after
  `KUDKEYS_IDLE_SECONDS` with no frames (default 30), ending gracefully with finals, so a
  stuck mic can't run up the bill.
- Audio is resampled to the 24 kHz OpenAI requires; the phone can record at 16 or 48 kHz.

## Config

| Env | Default | |
|---|---|---|
| `KUDKEYS_TOKENS` | required | Comma-separated, ≥ 24 chars each (`openssl rand -hex 32`) |
| `OPENAI_API_KEY` | — | Without it the server starts, and `/health` reports no transcription |
| `KUDKEYS_MODEL` | `gpt-live-transcribe` | |
| `KUDKEYS_LANGUAGES` | `id,en` | Default when the keyboard sends none |
| `KUDKEYS_DELAY` | unset | `minimal`…`xhigh`; sent only when set (support differs per model) |
| `KUDKEYS_NOISE_REDUCTION` | unset | `near_field` or `far_field` |
| `KUDKEYS_MAX_SESSION_SECONDS` | `600` | |
| `KUDKEYS_IDLE_SECONDS` | `30` | |
| `PORT` | `8080` | |

## Known gap

`gpt-live-transcribe` returns no confidence scores, so "ask me about unsure words" in the
full voice editor needs another signal: either a second pass through file transcription
with logprobs after stop, or the tidy-up model flagging names it can't place. Decide when
building v0.3.

## Tests

`bun test` runs the stream end to end against a fake OpenAI realtime server: resampling,
segment ordering, empty-commit avoidance, provider refusal, the session cap, and a missing key.
