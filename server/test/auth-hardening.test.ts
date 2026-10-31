// Review fixes on accounts (v0.2 review H1, M1, M2), against real Better Auth:
// - a database error during the session lookup answers 503, not 401, on
//   /api/me and the /v1/stream upgrade (a 401 signs the phone out);
// - the sign-in rate limit keys on cf-connecting-ip or the socket address,
//   never on a client-supplied X-Forwarded-For;
// - KUDKEYS_SIGNUP_OPEN=false refuses every sign-up.
//
// Rate-limit buckets live in one module-level map shared by every Better Auth
// instance in this process, so each test uses its own client IP.

import { afterAll, beforeAll, describe, expect, test } from 'bun:test'
import { memoryAdapter } from 'better-auth/adapters/memory'
import { createApp } from '../app'
import { createAuth, lookupSession, SIGN_IN_LIMIT, signUpOpen } from '../auth'
import { parseAllowlist } from '../lib/allowlist'
import { memoryStore } from '../lib/store'
import { createServer, streamAuthenticator } from '../server'
import { loadConfig } from '../voice/config'

const EMAIL = 'hardening@kudcrafts.com'
const PASSWORD = 'a-long-password-1'
const SECRET = 'test-secret-test-secret-test-secret-123'

// The session table can be made to throw, like Postgres behind a restarting pgbouncer.
let dbDown = false
let sessions: any[] = []
const authDb: Record<string, any[]> = { kudkeysUser: [], kudkeysAccount: [], kudkeysVerification: [] }
Object.defineProperty(authDb, 'kudkeysSession', {
  enumerable: true,
  get() {
    if (dbDown) throw new Error('connection to pgbouncer refused')
    return sessions
  },
  set(v: any[]) {
    sessions = v
  },
})

const allowlist = parseAllowlist(EMAIL)
const store = memoryStore(() => authDb.kudkeysUser as any)
const auth = createAuth({ database: memoryAdapter(authDb), allowlist, baseURL: 'http://localhost:8080', secret: SECRET, signUpOpen: true })
const getSession = (headers: Headers) => lookupSession(auth, headers)

let server: ReturnType<typeof createServer>
let base = ''

beforeAll(() => {
  const cfg = loadConfig({ PORT: '0', KUDKEYS_TOKENS: 'h'.repeat(32), GROQ_API_KEY: 'gsk-test' })
  const app = createApp({ store, allowlist, getSession, authHandler: (r) => auth.handler(r), transcription: null })
  server = createServer(cfg, { app, store, authenticate: streamAuthenticator({ tokens: cfg.tokens, getSession, store, allowlist }) })
  base = `http://localhost:${server.port}`
})
afterAll(() => server.stop(true))

const json = { 'content-type': 'application/json' }
const signIn = (headers: Record<string, string>, password = PASSWORD, host = base) =>
  fetch(`${host}/api/auth/sign-in/email`, { method: 'POST', headers: { ...json, ...headers }, body: JSON.stringify({ email: EMAIL, password }) })
const upgrade = (headers: Record<string, string>) =>
  fetch(`${base}/v1/stream`, { headers: { connection: 'Upgrade', upgrade: 'websocket', ...headers } })

let token = ''

describe('H1: a failed session lookup is 503, not "signed out"', () => {
  test('setup: sign up and sign in', async () => {
    const up = await fetch(`${base}/api/auth/sign-up/email`, {
      method: 'POST',
      headers: { ...json, 'cf-connecting-ip': '198.51.100.1' },
      body: JSON.stringify({ email: EMAIL, password: PASSWORD, name: 'H' }),
    })
    expect(up.status).toBe(200)
    const res = await signIn({ 'cf-connecting-ip': '198.51.100.1' })
    expect(res.status).toBe(200)
    token = res.headers.get('set-auth-token')!
    expect(token).toBeTruthy()
  })

  test('database down: /api/me and the stream upgrade answer 503 with a JSON error', async () => {
    dbDown = true
    try {
      const me = await fetch(`${base}/api/me`, { headers: { authorization: `Bearer ${token}` } })
      expect(me.status).toBe(503)
      expect(await me.json()).toEqual({ error: 'unavailable', reason: 'session_lookup_failed' })
      const ws = await upgrade({ authorization: `Bearer ${token}` })
      expect(ws.status).toBe(503)
      expect(await ws.json()).toEqual({ error: 'unavailable', reason: 'session_lookup_failed' })
    } finally {
      dbDown = false
    }
  })

  test('the same token works again once the database is back', async () => {
    expect((await fetch(`${base}/api/me`, { headers: { authorization: `Bearer ${token}` } })).status).toBe(200)
    expect(await upgrade({ authorization: `Bearer ${token}` }).then((r) => r.status)).not.toBe(401)
  })

  test('missing, invalid and revoked sessions are still 401', async () => {
    expect((await fetch(`${base}/api/me`)).status).toBe(401)
    expect((await fetch(`${base}/api/me`, { headers: { authorization: 'Bearer not-a-session' } })).status).toBe(401)
    expect((await upgrade({ authorization: `Bearer ${'z'.repeat(32)}` })).status).toBe(401)
    expect(await lookupSession(auth, new Headers({ authorization: 'Bearer garbage' }))).toBeNull()
  })
})

describe('M1: sign-in rate limit by cf-connecting-ip / socket address', () => {
  test(`the ${SIGN_IN_LIMIT.max + 1}th sign-in from one cf-connecting-ip in a minute is 429, whatever X-Forwarded-For says`, async () => {
    for (let i = 0; i < SIGN_IN_LIMIT.max; i++) {
      const res = await signIn({ 'cf-connecting-ip': '203.0.113.7', 'x-forwarded-for': `10.0.0.${i}` }, 'wrong-password-123')
      expect(res.status).toBe(401)
    }
    const blocked = await signIn({ 'cf-connecting-ip': '203.0.113.7', 'x-forwarded-for': '10.0.0.99' })
    expect(blocked.status).toBe(429)
    // another client is unaffected
    expect((await signIn({ 'cf-connecting-ip': '203.0.113.8' })).status).toBe(200)
  })

  test('without cf-connecting-ip the socket address is the key; spoofed headers do not open new buckets', async () => {
    const host = `http://127.0.0.2:${server.port}` // its own loopback address, its own bucket
    for (let i = 0; i < SIGN_IN_LIMIT.max; i++) {
      const res = await signIn({ 'x-forwarded-for': `192.0.2.${i}`, 'x-kudkeys-client-ip': `192.0.2.${i}` }, 'wrong-password-123', host)
      expect(res.status).toBe(401)
    }
    expect((await signIn({ 'x-forwarded-for': '192.0.2.200' }, PASSWORD, host)).status).toBe(429)
  })
})

describe('M2: KUDKEYS_SIGNUP_OPEN', () => {
  test('defaults to open; "false" closes it', () => {
    expect(signUpOpen({})).toBe(true)
    expect(signUpOpen({ KUDKEYS_SIGNUP_OPEN: 'true' })).toBe(true)
    expect(signUpOpen({ KUDKEYS_SIGNUP_OPEN: 'false' })).toBe(false)
    expect(signUpOpen({ KUDKEYS_SIGNUP_OPEN: ' FALSE ' })).toBe(false)
    expect(signUpOpen({ KUDKEYS_SIGNUP_OPEN: '0' })).toBe(false)
  })

  test('closed: even an allowlisted email cannot sign up, and existing accounts still sign in', async () => {
    const db: Record<string, any[]> = { kudkeysUser: [], kudkeysSession: [], kudkeysAccount: [], kudkeysVerification: [] }
    const open = createAuth({ database: memoryAdapter(db), allowlist, baseURL: 'http://localhost:8080', secret: SECRET, signUpOpen: true })
    const closed = createAuth({ database: memoryAdapter(db), allowlist, baseURL: 'http://localhost:8080', secret: SECRET, signUpOpen: false })
    const req = (a: typeof open, path: string, body: object, ip: string) =>
      a.handler(
        new Request(`http://localhost:8080/api/auth${path}`, {
          method: 'POST',
          headers: { ...json, 'x-kudkeys-client-ip': ip },
          body: JSON.stringify(body),
        }),
      )
    expect((await req(closed, '/sign-up/email', { email: EMAIL, password: PASSWORD, name: 'H' }, '198.51.100.20')).status).not.toBe(200)
    expect(db.kudkeysUser!.length).toBe(0)
    expect((await req(open, '/sign-up/email', { email: EMAIL, password: PASSWORD, name: 'H' }, '198.51.100.21')).status).toBe(200)
    expect((await req(closed, '/sign-in/email', { email: EMAIL, password: PASSWORD }, '198.51.100.22')).status).toBe(200)
  })
})
