// Accounts end to end, in-process: real Better Auth (memory adapter, bearer
// plugin) → the Hono app and /v1/stream → a fake Groq. Proves sign-up is
// allowlisted, the Android flow (sign in, bearer token) works, the profile is
// validated and reaches the Whisper prompt, and usage rows carry no text.

import { afterAll, beforeAll, describe, expect, test } from 'bun:test'
import { memoryAdapter } from 'better-auth/adapters/memory'
import { createApp } from '../app'
import { createAuth, lookupSession } from '../auth'
import { parseAllowlist } from '../lib/allowlist'
import { memoryStore } from '../lib/store'
import { periodStarts } from '../lib/usage'
import { createServer, streamAuthenticator } from '../server'
import { loadConfig } from '../voice/config'
import { connect, TOKEN } from './client'

const EMAIL = 'hammas@kudcrafts.com'
const PASSWORD = 'a-long-password-1'
const SPOKEN = 'phrase of' // what the fake Groq "hears"; must never reach a usage row

const authDb: Record<string, any[]> = { kudkeysUser: [], kudkeysSession: [], kudkeysAccount: [], kudkeysVerification: [] }
const allowlist = parseAllowlist(`${EMAIL},second@kudcrafts.com`)
const store = memoryStore(() => authDb.kudkeysUser as any)
const auth = createAuth({
  database: memoryAdapter(authDb),
  allowlist,
  baseURL: 'http://localhost:8080',
  secret: 'test-secret-test-secret-test-secret-123',
})
const getSession = (headers: Headers) => lookupSession(auth, headers)

let groq: ReturnType<typeof Bun.serve>
let server: ReturnType<typeof createServer>
let base = ''
const groqCalls: Record<string, string>[] = []

beforeAll(() => {
  groq = Bun.serve({
    port: 0,
    async fetch(req) {
      if (req.method !== 'POST') return new Response('not found', { status: 404 })
      const form = await req.formData()
      const fields: Record<string, string> = {}
      for (const [k, v] of form.entries()) if (typeof v === 'string') fields[k] = v
      groqCalls.push(fields)
      return Response.json({ text: SPOKEN, segments: [{ text: ` ${SPOKEN} hello`, avg_logprob: -0.1, no_speech_prob: 0.01 }] })
    },
  })
  const cfg = loadConfig({
    PORT: '0',
    KUDKEYS_TOKENS: TOKEN,
    GROQ_API_KEY: 'gsk-test',
    GROQ_TRANSCRIPTIONS_URL: `http://localhost:${groq.port}/openai/v1/audio/transcriptions`,
    KUDKEYS_IDLE_SECONDS: '5',
  })
  const app = createApp({ store, allowlist, getSession, authHandler: (r) => auth.handler(r), transcription: 'groq:whisper-large-v3' })
  server = createServer(cfg, { app, store, authenticate: streamAuthenticator({ tokens: cfg.tokens, getSession, store, allowlist }) })
  base = `http://localhost:${server.port}`
})

afterAll(() => {
  server.stop(true)
  groq.stop(true)
})

// The Android app sends no Origin and keeps no cookies.
const post = (path: string, body: unknown, headers: Record<string, string> = {}) =>
  fetch(`${base}${path}`, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) })
const bearer = (token: string) => ({ authorization: `Bearer ${token}` })

async function signUp(email: string) {
  return post('/api/auth/sign-up/email', { email, password: PASSWORD, name: 'Hammas' })
}
async function signIn(email = EMAIL): Promise<string> {
  const res = await post('/api/auth/sign-in/email', { email, password: PASSWORD })
  expect(res.status).toBe(200)
  const token = res.headers.get('set-auth-token')
  expect(token).toBeTruthy()
  return token!
}

const RATE = 16000
const tone = (ms: number) =>
  Int16Array.from({ length: (RATE * ms) / 1000 }, (_, i) => Math.round(5000 * Math.sin((2 * Math.PI * 300 * i) / RATE)))

async function stream(token: string, start: object = {}) {
  const c = connect(server.port!, token)
  await c.opened
  c.ws.send(JSON.stringify({ type: 'start', sampleRate: 16000, ...start }))
  await c.waitFor('ready')
  const t = tone(1000)
  for (let i = 0; i < t.length; i += 1600) {
    const f = t.subarray(i, i + 1600)
    c.ws.send(new Uint8Array(f.buffer, f.byteOffset, f.byteLength))
  }
  c.ws.send(JSON.stringify({ type: 'stop' }))
  expect(await c.closed).toBe(1000)
  return c.messages
}

async function upgradeStatus(headers: Record<string, string>) {
  return (await fetch(`${base}/v1/stream`, { headers: { connection: 'Upgrade', upgrade: 'websocket', ...headers } })).status
}

let token = ''

describe('accounts', () => {
  test('sign-up refuses an email outside KUDKEYS_ALLOWED_EMAILS', async () => {
    const res = await signUp('stranger@example.com')
    expect(res.status).toBe(403)
    expect(authDb.kudkeysUser!.length).toBe(0)
  })

  test('an allowed email signs up and gets a bearer token', async () => {
    const res = await signUp(EMAIL)
    expect(res.status).toBe(200)
    expect(res.headers.get('set-auth-token')).toBeTruthy()
  })

  test('sign-in returns the session token in set-auth-token', async () => {
    token = await signIn()
  })

  test('GET /api/me needs a session and returns the profile with defaults', async () => {
    expect((await fetch(`${base}/api/me`)).status).toBe(401)
    const res = await fetch(`${base}/api/me`, { headers: bearer(token) })
    expect(res.status).toBe(200)
    const me = await res.json()
    expect(me).toEqual({ id: expect.any(String), email: EMAIL, name: 'Hammas', image: null, voiceKeywords: [], languages: ['id', 'en'] })
  })

  test('health answers on /api/health and /health without a session', async () => {
    for (const p of ['/api/health', '/health']) {
      expect(await (await fetch(`${base}${p}`)).json()).toEqual({ ok: true, transcription: 'groq:whisper-large-v3' })
    }
  })
})

describe('PATCH /api/me', () => {
  const patch = (body: unknown) =>
    fetch(`${base}/api/me`, { method: 'PATCH', headers: { 'content-type': 'application/json', ...bearer(token) }, body: JSON.stringify(body) })

  test('refuses more than 100 keywords, keywords over 100 chars, and unknown fields', async () => {
    expect((await patch({ voiceKeywords: Array.from({ length: 101 }, (_, i) => `k${i}`) })).status).toBe(400)
    expect((await patch({ voiceKeywords: ['x'.repeat(101)] })).status).toBe(400)
    expect((await patch({ voiceKeywords: ['a<b'] })).status).toBe(400)
    expect((await patch({ languages: [] })).status).toBe(400)
    expect((await patch({ languages: ['indonesian'] })).status).toBe(400)
    const res = await patch({ email: 'other@example.com' })
    expect(res.status).toBe(400)
    expect(((await res.json()) as any).error).toBe('invalid')
    expect((await patch('not an object')).status).toBe(400)
  })

  test('accepts 100 keywords of 100 chars', async () => {
    const res = await patch({ voiceKeywords: Array.from({ length: 100 }, (_, i) => `${i}`.padEnd(100, 'x')) })
    expect(res.status).toBe(200)
  })

  test('saves name, keywords (trimmed, deduped) and languages', async () => {
    const res = await patch({ name: 'Hammas H', voiceKeywords: [' Fenno ', 'TypeMap', 'fenno'], languages: ['id'] })
    expect(res.status).toBe(200)
    expect(await res.json()).toMatchObject({ name: 'Hammas H', voiceKeywords: ['Fenno', 'TypeMap'], languages: ['id'] })
  })

  test('a cross-site browser write is refused', async () => {
    const res = await fetch(`${base}/api/me`, {
      method: 'PATCH',
      headers: { 'content-type': 'application/json', origin: 'https://evil.example', 'sec-fetch-site': 'cross-site', ...bearer(token) },
      body: JSON.stringify({ name: 'x' }),
    })
    expect(res.status).toBe(403)
  })
})

describe('/v1/stream auth', () => {
  test('no token, a wrong token, or a revoked session → 401', async () => {
    expect(await upgradeStatus({})).toBe(401)
    expect(await upgradeStatus(bearer('nope'))).toBe(401)
    expect(await upgradeStatus(bearer('x'.repeat(32)))).toBe(401)
  })

  test('a session token streams, with the profile merged into the prompt and languages', async () => {
    groqCalls.length = 0
    const messages = await stream(token, { keywords: ['Kudcrafts', 'fenno'] })
    expect(messages.find((m) => m.type === 'final')?.text).toContain(SPOKEN)
    // the keyboard's keywords first, then the profile's, without the duplicate
    expect(groqCalls[0]!.prompt).toBe('Kudcrafts, fenno, TypeMap')
    // `start` sent no languages → the profile's single language is passed to Whisper
    expect(groqCalls[0]!.language).toBe('id')
  })

  test('start languages still win over the profile', async () => {
    groqCalls.length = 0
    await stream(token, { languages: ['id', 'en'] })
    expect(groqCalls[0]!.language).toBeUndefined()
  })

  test('the usage row has numbers only — no transcript text — and is written for private fields too', async () => {
    const before = store.voiceSessions.length
    await stream(token, { private: true })
    await Bun.sleep(20)
    expect(store.voiceSessions.length).toBe(before + 1)
    const row = store.voiceSessions.at(-1)!
    expect(Object.keys(row).sort()).toEqual(['audioSeconds', 'endedAt', 'finals', 'id', 'model', 'outcome', 'session', 'startedAt', 'userId'])
    expect(row).toMatchObject({ outcome: 'stopped', finals: 1, model: 'whisper-large-v3' })
    expect(row.audioSeconds).toBeGreaterThan(0.9)
    expect(JSON.stringify(store.voiceSessions)).not.toContain(SPOKEN)
  })

  test('/api/me/usage lists the sessions and this week/month totals', async () => {
    const res = await fetch(`${base}/api/me/usage`, { headers: bearer(token) })
    const u: any = await res.json()
    expect(u.sessions.length).toBe(store.voiceSessions.length)
    expect(u.week.sessions).toBe(store.voiceSessions.length)
    expect(u.month.audioMinutes).toBeGreaterThan(0)
    expect(JSON.stringify(u)).not.toContain(SPOKEN)
  })

  test('the legacy static token still streams, and writes no usage row', async () => {
    const before = store.voiceSessions.length
    const messages = await stream(TOKEN)
    expect(messages.some((m) => m.type === 'final')).toBe(true)
    await Bun.sleep(20)
    expect(store.voiceSessions.length).toBe(before)
  })

  test('removing an email from the allowlist locks its sessions out of /api/me and /v1/stream', async () => {
    expect((await signUp('second@kudcrafts.com')).status).toBe(200)
    const second = await signIn('second@kudcrafts.com')
    expect(await upgradeStatus(bearer(second))).not.toBe(401)
    allowlist.delete('second@kudcrafts.com')
    expect(await upgradeStatus(bearer(second))).toBe(401)
    expect((await fetch(`${base}/api/me`, { headers: bearer(second) })).status).toBe(403)
  })

  test('sign-out as the app sends it (bearer + Origin of the server) revokes the token', async () => {
    const t = await signIn()
    expect((await post('/api/auth/sign-out', {}, { ...bearer(t), origin: 'http://localhost:8080' })).status).toBe(200)
    expect(await upgradeStatus(bearer(t))).toBe(401)
  })

  test('signing out revokes the token for the stream', async () => {
    const t = await signIn()
    expect((await post('/api/auth/sign-out', {}, bearer(t))).status).toBe(200)
    expect(await upgradeStatus(bearer(t))).toBe(401)
  })
})

describe('usage periods (Jakarta calendar)', () => {
  test('week starts Monday 00:00 WIB, month on the 1st', () => {
    // Sunday 2026-11-01 01:00 WIB = Saturday 31 Oct 18:00 UTC
    const p = periodStarts(new Date('2026-10-31T18:00:00Z'))
    expect(p.week.toISOString()).toBe('2026-10-25T17:00:00.000Z') // Mon 26 Oct 00:00 WIB
    expect(p.month.toISOString()).toBe('2026-10-31T17:00:00.000Z') // 1 Nov 00:00 WIB
  })
})
