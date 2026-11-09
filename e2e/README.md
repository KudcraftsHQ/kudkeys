# kudkeys e2e

Browser end-to-end checks for the web app (`app/`, served by the server), written with TesterArmy e2e (`e2e` 0.23,
`@e2e-dev/web`, `@e2e-dev/decision`). **This is where browser checks live**: a new one is a `*.e2e.ts` test here,
run through the queue, not a scratch Playwright script. The Android keyboard is not covered here.

- `tests/kudkeys.e2e.ts`: eight flow checks. The sign-in form (a wrong password is refused, then a sign-in lands on
  the profile), a voice keyword saved and still there after a reload, the usage page's sessions, word search and the
  Removed list, adding and removing a word, the backups list and deleting a backup, signing the phone keyboard out
  from Devices, and signing out. All deterministic `expect`s, no agent steps.
- `tests/mobile.e2e.ts` (tag `mobile`, titles `mobile layout › phone: …`): sign-in, profile, usage, words, backups
  and devices as a Pixel 7 (CDP device emulation, real touch swipes). It fails on a page that scrolls sideways, a
  bottom bar off the screen or two pinned bars covering each other, and first proves the screen loaded (its heading,
  no skeleton, no error text, real content). The measurements are cutroom's.

## Running

Only through the queue on hammas-dev, never directly (no `bunx e2e run` in a shell, no private browser):

```bash
e2e-queue run kudkeys --changed --wait     # while iterating: only the checks your branch touched
e2e-queue run kudkeys --grep mobile --wait # just the phone screens
e2e-queue run kudkeys --wait               # the whole suite, once before you finish
```

`BREAK=save` (in the env file) hides the Profile page's Save button: the deliberate red check.

## The fixture stack

`server/scripts/e2e-stack.ts` boots the real server (Hono + Better Auth + Prisma) serving a fresh build of the web
app, on the port block the runner gives the run in `E2E_PORT_BASE` (slot 0 = 4710, slot 1 = 4720, …; 4710 without
it):

- base: the server, the app and `/api/*` (`APP_URL`)
- base+1: a fake Groq (transcription and chat), so no paid or outbound call is ever made
- base+2: control (`CTL_URL`). `GET /status` answers once the stack is seeded; `GET /as/<owner|editor|devices>?next=/path`
  signs that user in (a real Better Auth session) and redirects to the app. Only the stack has it; the app has no such route.

**Its own database per port block**: `<name in KUDKEYS_E2E_DATABASE_URL>-p<base>` (`kudkeys-e2e-p4710`,
`kudkeys-e2e-p4720`…). The stack refuses any host but localhost and any name not starting `kudkeys-e2e`, so it can
never reach the worktree's dev database (a copy of production). It creates the database on first use, brings it up
with `prisma migrate deploy`, truncates it on every boot (after checking it is connected to that name) and seeds,
through the real API:

| User | Used by |
|---|---|
| `owner@keys.test` | read-only checks and every phone screen: words, settings, five backups, four voice sessions, a phone keyboard session |
| `editor@keys.test` | checks that change things (profile, words, backups, sign-out) |
| `devices@keys.test` | the Devices check, which signs its phone out |

The server gets an env built from nothing and `--no-env-file`, so the worktree's `.env` (dev database, real keys)
never reaches it; Groq points at the fake, OpenAI and the dev auto sign-in are off. The stack exits after `--ttl`
(default 15m) or on SIGTERM. Better Auth's sign-in limit (5 a minute per IP) still applies to the browser; the
stack's own sign-ins each say they come from their own address.

Registration (once):

```bash
e2e-queue app add kudkeys --repo /Users/hammashamzah/Projects/.worktrees/kudkeys-e2e --dir e2e \
  --stack "bun server/scripts/e2e-stack.ts --ttl 15m" \
  --ready-url 'http://127.0.0.1:{port+2}/status' \
  --envfile /Users/hammashamzah/.config/e2e-queue/kudkeys.env --parallel
```

The stack command runs from the repo root, which needs `bun install` (root) and `bun install` in `e2e/`. The env file
(chmod 600, outside the repo) holds `KUDKEYS_E2E_DATABASE_URL`, a local Postgres URL whose database name is
`kudkeys-e2e` and whose role may create databases. Agent steps, when a check needs one, take `TYPESAFE_AI_API_KEY`
and `OPENROUTER_API_KEY` from the runner (`~/.config/e2e-queue/keys.env`).

## The replay cache is committed

`cache/` holds one JSON file per recorded agent step (none yet: every check is deterministic). The target pins
`app.identity` to `http://127.0.0.1:4710/`, so entries recorded on slot 0 replay on every port block. The cache is
read-only by default; to record a new agent step or heal a changed one, run once with `E2E_CACHE_MODE=read-write`
in the env file, then commit the changed `cache/*.json` after reading the diff.

`.e2e/` (report, results, videos) is ignored by the root `.gitignore`.
