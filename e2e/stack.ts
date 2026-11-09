// Where the fixture stack (server/scripts/e2e-stack.ts) answers, for the config and the tests.
//
// The e2e-queue runner gives each suite run a port block in E2E_PORT_BASE (slot 0 = 4710, slot 1 = 4720, …) and
// boots the stack with the same env: the server on base, a fake Groq on base+1, control on base+2. APP_URL / CTL_URL
// still win when set. Without E2E_PORT_BASE it is slot 0.

function portBase(): number {
  const raw = process.env.E2E_PORT_BASE
  if (raw === undefined || raw === '') return 4710
  const n = Number(raw)
  if (!Number.isInteger(n) || n < 1024 || n > 65_533) throw new Error(`E2E_PORT_BASE must be a port (1024-65533), got ${raw}`)
  return n
}

export const PORT_BASE = portBase()
export const APP_URL = process.env.APP_URL ?? `http://127.0.0.1:${PORT_BASE}`
export const CTL_URL = process.env.CTL_URL ?? `http://127.0.0.1:${PORT_BASE + 2}`

/**
 * The stack's seeded people. owner: what the read-only checks and phone screens look at; editor: what checks change;
 * devices: signed in only on its phone keyboard, which the Devices check signs out.
 */
export const USERS = {
  owner: { email: 'owner@keys.test', password: 'owner-pass-1234' },
  editor: { email: 'editor@keys.test', password: 'editor-pass-1234' },
  devices: { email: 'devices@keys.test', password: 'devices-pass-1234' },
} as const

/** GET /as/<user>?next=… on the control server signs that user in and redirects to the app */
export const as = (who: keyof typeof USERS, next: string) => `${CTL_URL}/as/${who}?next=${encodeURIComponent(next)}`

/** the web engine handle e2e.config.ts builds, for surfaceOf() in a test (the worker evaluates the config itself) */
export const ENGINE_KEY = Symbol.for('kudkeys.e2e.web-engine')
