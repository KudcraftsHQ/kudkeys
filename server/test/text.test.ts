// /api/text/* in-process: real Better Auth (memory adapter, bearer plugin) →
// the Hono app → a fake Groq chat server. Proves session-only auth, input
// validation, the profile in the prompt, question offsets checked in code,
// one retry on bad JSON, 429/503 mapping, the per-user limit, and usage rows
// that never hold text.

import { afterAll, beforeAll, beforeEach, describe, expect, test } from 'bun:test'
import { memoryAdapter } from 'better-auth/adapters/memory'
import { createApp } from '../app'
import { createAuth, lookupSession } from '../auth'
import { parseAllowlist } from '../lib/allowlist'
import { memoryStore } from '../lib/store'
import { createTextEngine, placeQuestions } from '../text/engine'
import { loadConfig } from '../voice/config'
import { TOKEN } from './client'

const EMAIL = 'hammas@kudcrafts.com'
const PASSWORD = 'a-long-password-1'
const SECRET_TEXT = 'tadi feno bilang rahasia banget' // must never reach a usage row

type Reply = (body: any) => Response | Promise<Response>
const chat = (content: unknown): Response =>
  Response.json({
    choices: [{ message: { content: typeof content === 'string' ? content : JSON.stringify(content) }, finish_reason: 'stop' }],
  })

let replies: Reply[] = []
let calls: any[] = []
let groq: ReturnType<typeof Bun.serve>

const authDb: Record<string, any[]> = { kudkeysUser: [], kudkeysSession: [], kudkeysAccount: [], kudkeysVerification: [] }
const allowlist = parseAllowlist(EMAIL)
const store = memoryStore(() => authDb.kudkeysUser as any)
const auth = createAuth({
  database: memoryAdapter(authDb),
  allowlist,
  baseURL: 'http://localhost:8080',
  secret: 'test-secret-test-secret-test-secret-123',
})
const getSession = (headers: Headers) => lookupSession(auth, headers)

let app: ReturnType<typeof createApp>
let limited: ReturnType<typeof createApp>
let token = ''

beforeAll(async () => {
  groq = Bun.serve({
    port: 0,
    async fetch(req) {
      const body = await req.json()
      calls.push(body)
      const next = replies.shift()
      return next ? next(body) : new Response('no scripted reply', { status: 500 })
    },
  })
  const engine = createTextEngine(
    { apiKey: 'gsk-test', url: `http://localhost:${groq.port}/openai/v1/chat/completions`, model: 'test/model' },
    { timeoutMs: { fix: 300, tidy: 2000, instruct: 2000 } },
  )
  const base = { store, allowlist, getSession, authHandler: (r: Request) => auth.handler(r), transcription: null }
  app = createApp({ ...base, text: { engine, perMinute: 1000 } })
  limited = createApp({ ...base, text: { engine, perMinute: 2 } })

  const post = (path: string, body: unknown) =>
    app.fetch(new Request(`http://localhost:8080${path}`, {
        method: 'POST',
        // Its own client IP: Better Auth's sign-in limit must not share a bucket with other test files.
        headers: { 'content-type': 'application/json', 'x-kudkeys-client-ip': '198.51.100.77' },
        body: JSON.stringify(body),
      }),
    )
  expect((await post('/api/auth/sign-up/email', { email: EMAIL, password: PASSWORD, name: 'Hammas' })).status).toBe(200)
  const res = await post('/api/auth/sign-in/email', { email: EMAIL, password: PASSWORD })
  token = res.headers.get('set-auth-token')!
  expect(token).toBeTruthy()
  const user = authDb.kudkeysUser![0]
  user.voiceKeywords = ['Fenno', 'TypeMap']
  user.languages = ['id', 'en']
})

afterAll(() => groq.stop(true))
beforeEach(() => {
  replies = []
  calls = []
  store.textUsage.length = 0
})

function call(path: string, body: unknown, opts: { auth?: string | null; target?: ReturnType<typeof createApp> } = {}) {
  const headers: Record<string, string> = { 'content-type': 'application/json' }
  const a = opts.auth === undefined ? token : opts.auth
  if (a) headers.authorization = `Bearer ${a}`
  return (opts.target ?? app).fetch(
    new Request(`http://localhost:8080/api/text${path}`, { method: 'POST', headers, body: typeof body === 'string' ? body : JSON.stringify(body) }),
  )
}

describe('auth', () => {
  test('a session token works', async () => {
    replies = [() => chat({ text: 'Oke.', changed: true })]
    const res = await call('/fix', { text: 'oke' })
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ text: 'Oke.', changed: true })
  })

  test('no token is 401', async () => {
    expect((await call('/fix', { text: 'oke' }, { auth: null })).status).toBe(401)
  })

  test('a legacy static stream token is refused', async () => {
    expect((await call('/tidy', { text: 'oke' }, { auth: TOKEN })).status).toBe(401)
    expect(calls).toHaveLength(0)
  })
})

describe('validation', () => {
  test.each([
    ['/tidy', {}],
    ['/tidy', { text: 'x'.repeat(20_001) }],
    ['/tidy', { text: 'ok', segments: [{ text: 'ok', confidence: 2 }] }],
    ['/fix', { text: 'x'.repeat(4_001) }],
    ['/fix', { text: 'ok', extra: 1 }],
    ['/fix', { text: 'ok', languages: ['Indonesian'] }],
    ['/instruct', { instruction: '   ', text: 'ok' }],
    ['/instruct', { instruction: 'x'.repeat(2_001), text: '' }],
    ['/instruct', { instruction: 'shorter' }],
    ['/fix', 'not json'],
  ])('%s %j → 400', async (path, body) => {
    const res = await call(path, body)
    expect(res.status).toBe(400)
    expect(((await res.json()) as any).error).toBe('invalid')
    expect(calls).toHaveLength(0)
  })
})

describe('tidy', () => {
  test('profile keywords and languages are in the prompt; the text goes as JSON in the user message', async () => {
    replies = [() => chat({ text: 'Tadi Fenno bilang.', questions: [] })]
    const res = await call('/tidy', { text: 'tadi feno bilang', segments: [{ text: 'tadi feno', confidence: 0.4 }] })
    expect(res.status).toBe(200)
    const [req] = calls
    expect(req.model).toBe('test/model')
    expect(req.response_format).toEqual({ type: 'json_object' })
    expect(req.messages[0].content).toContain('Fenno, TypeMap')
    expect(req.messages[0].content).toContain('Indonesian, English')
    expect(JSON.parse(req.messages[1].content)).toEqual({ transcript: 'tadi feno bilang', unsure: ['tadi feno'] })
  })

  test('the request languages override the profile', async () => {
    replies = [() => chat({ text: 'Hi.', questions: [] })]
    await call('/tidy', { text: 'hi', languages: ['en'] })
    expect(calls[0].messages[0].content).toContain('speaks English and')
    expect(calls[0].messages[0].content).not.toContain('Indonesian, English')
  })

  test('question offsets are computed and checked here; bad ones are dropped', async () => {
    const text = 'Tadi Feno bilang ke Darsono, terus Feno pergi.'
    replies = [
      () =>
        chat({
          text,
          questions: [
            { word: 'Feno', options: ['Fenno', 'Feno'], start: 33 }, // offset slightly off: nearest occurrence (35)
            { word: 'Darsono', options: ['Darsono', 'Darsana'], start: 999 }, // offset nonsense: found by search
            { word: 'Budi', options: ['Bodi'] }, // not in the text: dropped
            { word: 'bilang', options: [] }, // no options: dropped
            { word: 'ke', options: ['ke.', ' ke', 'ke?'] }, // punctuation-only options: dropped
            { word: 'Fen', options: ['Fan'] }, // only inside a word: dropped
            { word: 'Feno', options: ['Fenno'], start: 5 }, // exact
          ],
        }),
    ]
    const body = (await (await call('/tidy', { text: 'tadi feno bilang ke darsono terus feno pergi' })).json()) as any
    expect(body.text).toBe(text)
    expect(body.questions).toEqual([
      { id: 'q1', original: 'Feno', start: 5, end: 9, options: ['Fenno'] },
      { id: 'q2', original: 'Darsono', start: 20, end: 27, options: ['Darsana'] },
      { id: 'q3', original: 'Feno', start: 35, end: 39, options: ['Fenno'] },
    ])
    for (const q of body.questions) expect(body.text.slice(q.start, q.end)).toBe(q.original)
  })

  test('empty text needs no model call', async () => {
    const res = await call('/tidy', { text: '  ' })
    expect(await res.json()).toEqual({ text: '', questions: [] })
    expect(calls).toHaveLength(0)
  })
})

describe('fix', () => {
  test('changed is decided by comparing, not by the model', async () => {
    replies = [() => chat({ text: 'Oke, makasih!', changed: true })]
    expect(await (await call('/fix', { text: 'Oke, makasih!' })).json()).toEqual({ text: 'Oke, makasih!', changed: false })
  })
})

describe('instruct', () => {
  test('empty text means write from the instruction', async () => {
    replies = [() => chat({ text: "Sorry, I'll be 10 minutes late." })]
    const res = await call('/instruct', { instruction: "say I'll be 10 minutes late", text: '' })
    expect(await res.json()).toEqual({ text: "Sorry, I'll be 10 minutes late." })
    expect(JSON.parse(calls[0].messages[1].content)).toEqual({ instruction: "say I'll be 10 minutes late", text: '' })
  })
})

describe('model failures', () => {
  test('invalid JSON is retried once', async () => {
    replies = [() => chat('not json {'), () => chat({ text: 'Oke.', changed: true })]
    const res = await call('/fix', { text: 'oke' })
    expect(res.status).toBe(200)
    expect(calls).toHaveLength(2)
  })

  test("Groq's json_validate_failed and a wrong shape are retried too", async () => {
    replies = [() => Response.json({ error: { code: 'json_validate_failed' } }, { status: 400 }), () => chat({ text: 'Oke.' })]
    expect((await call('/instruct', { instruction: 'x', text: 'y' })).status).toBe(200)
    replies = [() => chat({ wrong: true }), () => chat({ text: 'Oke.' })]
    expect((await call('/instruct', { instruction: 'x', text: 'y' })).status).toBe(200)
  })

  test('invalid twice → 503', async () => {
    replies = [() => chat('nope'), () => chat({ text: 42 })]
    const res = await call('/fix', { text: 'oke' })
    expect(res.status).toBe(503)
    expect(await res.json()).toEqual({ error: 'unavailable', reason: 'invalid_model_output' })
    expect(calls).toHaveLength(2)
  })

  test('Groq 429 → 429 with its Retry-After', async () => {
    replies = [() => new Response('{}', { status: 429, headers: { 'retry-after': '7' } })]
    const res = await call('/fix', { text: 'oke' })
    expect(res.status).toBe(429)
    expect(res.headers.get('retry-after')).toBe('7')
    expect(calls).toHaveLength(1) // not retried
  })

  test('Groq 5xx or a refused key → 503', async () => {
    replies = [() => new Response('{}', { status: 502 })]
    expect((await call('/fix', { text: 'oke' })).status).toBe(503)
    replies = [() => new Response('{}', { status: 401 })]
    expect((await call('/fix', { text: 'oke' })).status).toBe(503)
  })

  test('a timeout → 503', async () => {
    replies = [async () => (await Bun.sleep(1000), chat({ text: 'late' }))]
    const res = await call('/fix', { text: 'oke' })
    expect(res.status).toBe(503)
    expect(((await res.json()) as any).reason).toBe('timeout')
  })

  test('no GROQ_API_KEY → 503', async () => {
    const bare = createApp({ store, allowlist, getSession, transcription: null })
    expect((await call('/fix', { text: 'oke' }, { target: bare })).status).toBe(503)
  })
})

test('per-user rate limit → 429 with Retry-After', async () => {
  replies = [() => chat({ text: 'A.' }), () => chat({ text: 'B.' })]
  expect((await call('/instruct', { instruction: 'a', text: '' }, { target: limited })).status).toBe(200)
  expect((await call('/instruct', { instruction: 'b', text: '' }, { target: limited })).status).toBe(200)
  const res = await call('/instruct', { instruction: 'c', text: '' }, { target: limited })
  expect(res.status).toBe(429)
  expect(Number(res.headers.get('retry-after'))).toBeGreaterThan(0)
  expect(calls).toHaveLength(2)
})

describe('usage rows', () => {
  test('counts and timing, never text', async () => {
    replies = [() => chat({ text: 'Tadi Fenno bilang rahasia banget.', questions: [] })]
    await call('/tidy', { text: SECRET_TEXT })
    await Bun.sleep(5)
    expect(store.textUsage).toHaveLength(1)
    const row = store.textUsage[0]!
    expect(row).toMatchObject({ kind: 'tidy', charsIn: SECRET_TEXT.length, charsOut: 33, model: 'test/model', ok: true })
    expect(Object.keys(row).sort()).toEqual(['charsIn', 'charsOut', 'kind', 'model', 'ms', 'ok', 'userId'])
    const dump = JSON.stringify(store.textUsage)
    expect(dump).not.toContain('rahasia')
    expect(dump).not.toContain('Fenno')
  })

  test('private: no character counts either', async () => {
    replies = [() => chat({ text: 'Oke.', changed: true })]
    await call('/fix', { text: SECRET_TEXT, private: true })
    await Bun.sleep(5)
    expect(store.textUsage[0]).toMatchObject({ kind: 'fix', charsIn: null, charsOut: null, ok: true })
  })

  test('a failed call is recorded as not ok', async () => {
    replies = [() => new Response('{}', { status: 503 })]
    await call('/fix', { text: 'oke' })
    await Bun.sleep(5)
    expect(store.textUsage[0]).toMatchObject({ kind: 'fix', charsIn: 3, charsOut: null, ok: false })
  })
})

test('placeQuestions never returns overlapping or mismatched spans', () => {
  const text = 'Feno Feno'
  const qs = placeQuestions(text, [
    { word: 'Feno', options: ['Fenno'], start: 0 },
    { word: 'Feno', options: ['Fenno'], start: 0 },
    { word: 'Feno', options: ['Fenno'], start: 0 },
  ])
  expect(qs.map((q) => [q.start, q.end])).toEqual([
    [0, 4],
    [5, 9],
  ])
})

test('tidy can run on its own model; the usage row names the model used', async () => {
  const split = createTextEngine({ apiKey: 'gsk-test', url: `http://localhost:${groq.port}/v1/chat/completions`, model: 'big/model', tidyModel: 'fast/model' })
  expect(split.models).toEqual({ tidy: 'fast/model', fix: 'big/model', instruct: 'big/model' })
  const splitApp = createApp({ store, allowlist, getSession, transcription: null, text: { engine: split } })
  replies = [() => chat({ text: 'Halo.', questions: [] }), () => chat({ text: 'Halo.', changed: true })]
  await call('/tidy', { text: 'halo' }, { target: splitApp })
  await call('/fix', { text: 'halo' }, { target: splitApp })
  await Bun.sleep(5)
  expect(calls.map((c) => c.model)).toEqual(['fast/model', 'big/model'])
  expect(store.textUsage.map((r) => r.model)).toEqual(['fast/model', 'big/model'])
})

test('config: defaults per kind; KUDKEYS_TEXT_MODEL sets all; KUDKEYS_TIDY_MODEL overrides tidy', () => {
  expect(loadConfig({}).text).toMatchObject({ model: 'openai/gpt-oss-120b', tidyModel: 'qwen/qwen3.8-27b', perMinute: 30, apiKey: null })
  expect(loadConfig({ KUDKEYS_TEXT_MODEL: 'a/b' }).text).toMatchObject({ model: 'a/b', tidyModel: 'a/b' })
  expect(loadConfig({ KUDKEYS_TEXT_MODEL: 'a/b', KUDKEYS_TIDY_MODEL: 'c/d' }).text).toMatchObject({ model: 'a/b', tidyModel: 'c/d' })
})
