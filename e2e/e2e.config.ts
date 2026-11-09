import type { E2EConfig } from 'e2e'
import { web } from '@e2e-dev/web'
import { decisionExecutor } from '@e2e-dev/decision'
import { typeSafeAi } from '@ai-sdk/typesafe-ai'
import { openrouter } from '@openrouter/ai-sdk-provider'
import { APP_URL, ENGINE_KEY } from './stack'

// BREAK=save hides the Profile page's Save button: the deliberate red check, to see that a broken control comes back
// red with the step named.
const breakScripts =
  process.env.BREAK === 'save'
    ? [
        `new MutationObserver(() => document.querySelectorAll('button[type="submit"]').forEach((b) => {
          if (/^save/i.test((b.textContent || '').trim())) b.remove();
        })).observe(document, { subtree: true, childList: true });`,
      ]
    : []

// A fresh headless Chromium per run (the e2e-queue `test` lane). Never a shared browser: a persistent profile keeps
// state from earlier runs.
const engine = web({
  viewport: { width: 1280, height: 800 },
  // Any header turns on request routing, which also keeps service workers from attaching (they hang a step).
  headers: { 'x-e2e': '1' },
  initScripts: breakScripts,
})
// tests/mobile.e2e.ts reaches the live page through surfaceOf(engine) for phone emulation and real touch input
Reflect.set(globalThis, ENGINE_KEY, engine)

export default {
  tests: 'tests/**/*.e2e.ts',
  targets: [
    {
      engine,
      app: {
        // the stack's port block (stack.ts): http://127.0.0.1:<E2E_PORT_BASE>, 4710 without one
        url: APP_URL,
        // The replay cache keys on the app's identity, which defaults to the URL's origin: on another port block every
        // committed entry would miss and run live. Pinned to what 4710 derives, so entries recorded on slot 0 replay on
        // every slot. Every slot runs the same checkout, so it is the same app.
        identity: 'http://127.0.0.1:4710/',
      },
    },
  ],
  workers: 1,
  // The replay cache is committed (see README): one JSON file per agent step, keyed by digests of the project id (this
  // package's name), the test, the instruction and the app identity. read-only by default, so a red run cannot evict
  // committed entries; record or heal on purpose with E2E_CACHE_MODE=read-write and commit the diff.
  cache: { mode: process.env.E2E_CACHE_MODE === 'read-write' ? 'read-write' : 'read-only', dir: 'cache' },
  agents: {
    default: {
      // keys: TYPESAFE_AI_API_KEY and OPENROUTER_API_KEY, from the runner's env (~/.config/e2e-queue/keys.env)
      executor: decisionExecutor({
        model: typeSafeAi.decisionModel(process.env.TYPESAFE_MODEL || 'jev-latest'),
        textModel: openrouter(process.env.TEXT_MODEL || 'inception/mercury-2.5'),
      }),
    },
  },
} satisfies E2EConfig
