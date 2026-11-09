# Kud Keys — agent notes

Layout and how to run things: [README.md](README.md), [server/README.md](server/README.md).

## Browser checks live in `e2e/`

The web app's end-to-end checks are a TesterArmy e2e suite in [`e2e/`](e2e/README.md), run only through the
e2e-queue runner (`e2e-queue run kudkeys`), against the fixture stack `server/scripts/e2e-stack.ts` (the real
server on its own seeded `kudkeys-e2e-p<port>` database, Groq faked). Results: https://e2e.kudcrafts.com.

- While iterating: `e2e-queue run kudkeys --changed --wait` (only the checks your branch touched).
- Once at the end: `e2e-queue run kudkeys --wait` (the whole suite), and put the run link in your reply.
- A new or changed browser check goes in `e2e/tests/` as a `*.e2e.ts` test, committed with your change, not a
  scratch script. A new phone screen goes in `e2e/tests/mobile.e2e.ts`, as long as the stack seeds what it shows.
  Data a check needs is seeded in `server/scripts/e2e-stack.ts`.
- Never launch Chromium or Playwright outside the queue, and never point the stack at the dev database (it is a
  copy of production): the stack refuses anything but a local `kudkeys-e2e*` database.
