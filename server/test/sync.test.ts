// /api/sync/*, /api/words and /api/backups in-process: real Better Auth
// (memory adapter, bearer plugin) → the Hono app → the real SQL on PGlite
// (Postgres in WASM) with the real migrations. Proves session-only auth, the
// size limits, the word merge rules (union, max count, tombstone ordering),
// import dedupe, per-key settings merge, the backup round trip with its
// sha256, retention pruning, and that no word, setting or backup byte is logged.
//
// Every word here is made up.

import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, spyOn, test } from 'bun:test'
import { PGlite } from '@electric-sql/pglite'
import { memoryAdapter } from 'better-auth/adapters/memory'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { createApp } from '../app'
import { createAuth, lookupSession } from '../auth'
import { parseAllowlist } from '../lib/allowlist'
import type { Sql } from '../lib/sql'
import { memoryStore } from '../lib/store'
import { LIMITS } from '../routes/sync'
import { retained } from '../sync/backups'
import { mergePrefs } from '../sync/settings'
import { canonicalLocale } from '../sync/words'
import { TOKEN } from './client'

const EMAIL = 'sync-tester@kudcrafts.com'
const PASSWORD = 'a-long-password-1'
const H = 3600_000
const T0 = Date.parse('2026-10-20T03:00:00.000Z')

const pg = new PGlite()
const sql: Sql = async (text, params) => (await pg.query(text, params as any[])).rows as any[]
const authDb: Record<string, any[]> = { kudkeysUser: [], kudkeysSession: [], kudkeysAccount: [], kudkeysVerification: [] }
const allowlist = parseAllowlist(EMAIL)
const auth = createAuth({
  database: memoryAdapter(authDb),
  allowlist,
  baseURL: 'http://localhost:8080',
  secret: 'test-secret-test-secret-test-secret-123',
})
let clock = T0
const app = createApp({
  store: memoryStore(() => authDb.kudkeysUser as any),
  allowlist,
  getSession: (h) => lookupSession(auth, h),
  authHandler: (r) => auth.handler(r),
  transcription: null,
  sync: { sql },
  now: () => clock,
})
let token = ''
let userId = ''

const call = (method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
  app.fetch(
    new Request(`http://localhost:8080${path}`, {
      method,
      headers: {
        ...(body !== undefined && !(body instanceof Uint8Array) ? { 'content-type': 'application/json' } : {}),
        ...(token ? { authorization: `Bearer ${token}` } : {}),
        ...headers,
      },
      body: body === undefined ? undefined : body instanceof Uint8Array ? (body as Uint8Array<ArrayBuffer>) : JSON.stringify(body),
    }),
  )
const json = async (res: Response | Promise<Response>) => {
  const r = await res
  return { status: r.status, body: (await r.json()) as any }
}
const w = (word: string, over: Partial<{ locale: string; count: number; source: string; deleted: boolean; updatedAt: string | number }> = {}) => ({
  word,
  locale: 'en_US',
  count: 1,
  source: 'typed',
  deleted: false,
  updatedAt: new Date(clock).toISOString(),
  ...over,
})
const allWords = async () => (await json(call('GET', '/api/sync/words'))).body.words as any[]
// rows are sent as en_US (as older clients and imports might) and stored as the canonical language, en
const find = async (word: string, locale = 'en') => (await allWords()).find((x) => x.word === word && x.locale === locale)

beforeAll(async () => {
  const dir = join(import.meta.dir, '../../prisma/migrations')
  for (const m of readdirSync(dir).filter((d) => !d.endsWith('.toml')).sort()) await pg.exec(readFileSync(join(dir, m, 'migration.sql'), 'utf8'))
  const post = (path: string, body: unknown) =>
    app.fetch(
      new Request(`http://localhost:8080${path}`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', 'x-kudkeys-client-ip': '198.51.100.91' },
        body: JSON.stringify(body),
      }),
    )
  expect((await post('/api/auth/sign-up/email', { email: EMAIL, password: PASSWORD, name: 'Sync Tester' })).status).toBe(200)
  token = (await post('/api/auth/sign-in/email', { email: EMAIL, password: PASSWORD })).headers.get('set-auth-token')!
  userId = authDb.kudkeysUser![0].id
  await pg.query(`INSERT INTO kudkeys_user (id, email, name, "updatedAt") VALUES ($1, $2, 'Sync Tester', now())`, [userId, EMAIL])
})

afterAll(() => pg.close())
beforeEach(() => {
  clock = T0
})

describe('auth', () => {
  test('no token, a bad token and a legacy stream token are all 401', async () => {
    for (const auth of [undefined, 'Bearer nope', `Bearer ${TOKEN}`]) {
      for (const [m, p] of [
        ['GET', '/api/sync/settings'],
        ['GET', '/api/sync/words'],
        ['POST', '/api/sync/words'],
        ['GET', '/api/backups'],
        ['GET', '/api/words'],
      ]) {
        const res = await app.fetch(new Request(`http://localhost:8080${p}`, { method: m, headers: auth ? { authorization: auth } : {} }))
        expect(res.status).toBe(401)
      }
    }
  })

  test('a cross-site cookie write is refused', async () => {
    const res = await call('POST', '/api/sync/words', { words: [] }, { origin: 'https://evil.example' })
    expect(res.status).toBe(403)
  })
})

describe('words', () => {
  test('validation: bad rows, more than 5000 per call', async () => {
    let r = await json(call('POST', '/api/sync/words', { words: [w('zorblat', { source: 'stolen' })] }))
    expect(r.status).toBe(400)
    expect(r.body.error).toBe('invalid')
    r = await json(call('POST', '/api/sync/words', { words: [w(' padded ')] }))
    expect(r.status).toBe(400)
    r = await json(call('POST', '/api/sync/words', { words: [w('x'.repeat(49))] }))
    expect(r.status).toBe(400)
    r = await json(call('POST', '/api/sync/words', { words: [{ ...w('zorblat'), extra: 1 }] }))
    expect(r.status).toBe(400)
    r = await json(call('POST', '/api/sync/words', { words: Array.from({ length: 5001 }, (_, i) => w(`fake${i}`)) }))
    expect(r.status).toBe(400)
    expect(r.body.issues[0].message).toContain('5000')
    r = await json(call('GET', '/api/sync/words?since=yesterday'))
    expect(r.status).toBe(400)
  })

  test('5000 in one call is fine', async () => {
    const r = await json(call('POST', '/api/sync/words', { words: Array.from({ length: 5000 }, (_, i) => w(`bulkfake${i}`, { locale: 'xx' })) }))
    expect(r.body).toEqual({ accepted: 5000, changed: 5000, now: new Date(T0 - LIMITS.sinceOverlapMs).toISOString() })
  })

  test('union by (word, locale) and count = max, both ways', async () => {
    await call('POST', '/api/sync/words', { words: [w('glimbo', { count: 5 }), w('glimbo', { locale: 'id', count: 2 })] })
    clock += H
    await call('POST', '/api/sync/words', { words: [w('glimbo', { count: 3 })] })
    expect((await find('glimbo'))!.count).toBe(5)
    await call('POST', '/api/sync/words', { words: [w('glimbo', { count: 9 })] })
    expect((await find('glimbo'))!.count).toBe(9)
    expect((await find('glimbo', 'id'))!.count).toBe(2)
  })

  test('a newer tombstone wins; an older add does not revive it; a newer add does', async () => {
    await call('POST', '/api/sync/words', { words: [w('frumple', { count: 4, updatedAt: T0 })] })
    clock = T0 + 2 * H
    await call('POST', '/api/sync/words', { words: [w('frumple', { deleted: true, updatedAt: T0 + 2 * H })] })
    expect((await find('frumple'))!.deleted).toBe(true)
    // a phone that hasn't synced yet sends its older add, with a higher count
    await call('POST', '/api/sync/words', { words: [w('frumple', { count: 7, updatedAt: T0 + H })] })
    const f = (await find('frumple'))!
    expect(f.deleted).toBe(true)
    expect(f.count).toBe(7)
    expect(f.updatedAt).toBe(new Date(T0 + 2 * H).toISOString())
    // added again later (e.g. on the web), from another source
    clock = T0 + 3 * H
    await call('POST', '/api/sync/words', { words: [w('frumple', { source: 'manual', updatedAt: T0 + 3 * H })] })
    const g = (await find('frumple'))!
    expect(g.deleted).toBe(false)
    expect(g.source).toBe('manual')
  })

  test('a tie goes to the delete, also inside one batch; source stays the first one seen', async () => {
    await call('POST', '/api/sync/words', { words: [w('quazzle', { updatedAt: T0 })] })
    await call('POST', '/api/sync/words', { words: [w('quazzle', { deleted: true, updatedAt: T0 })] })
    expect((await find('quazzle'))!.deleted).toBe(true)
    await call('POST', '/api/sync/words', { words: [w('blorpine', { deleted: true, updatedAt: T0 }), w('blorpine', { updatedAt: T0, count: 3 })] })
    const b = (await find('blorpine'))!
    expect(b).toMatchObject({ deleted: true, count: 3 })
    await call('POST', '/api/sync/words', { words: [w('snerk', { source: 'swiftkey' })] })
    await call('POST', '/api/sync/words', { words: [w('snerk', { source: 'typed', count: 6, updatedAt: T0 + H })] })
    expect((await find('snerk'))!).toMatchObject({ source: 'swiftkey', count: 6 })
  })

  test('a future device time is clamped to the server clock', async () => {
    await call('POST', '/api/sync/words', { words: [w('timeywimp', { updatedAt: T0 + 365 * 24 * H })] })
    expect((await find('timeywimp'))!.updatedAt).toBe(new Date(T0).toISOString())
    clock = T0 + H
    await call('POST', '/api/sync/words', { words: [w('timeywimp', { deleted: true, updatedAt: T0 + H })] })
    expect((await find('timeywimp'))!.deleted).toBe(true)
  })

  test('?since= returns only what changed (tombstones included), not no-op writes', async () => {
    clock = T0 + 10 * H
    await call('POST', '/api/sync/words', { words: [w('sincefoo', { updatedAt: clock }), w('sincebar', { updatedAt: clock })] })
    clock = T0 + 11 * H
    const first = await json(call('GET', '/api/sync/words?since=' + new Date(T0 + 10 * H - 1).toISOString()))
    expect(first.body.words.map((x: any) => x.word).sort()).toEqual(['sincebar', 'sincefoo'])
    expect(first.body.now).toBe(new Date(T0 + 11 * H - LIMITS.sinceOverlapMs).toISOString())
    clock = T0 + 12 * H
    // same data again: nothing changes, so nothing is new
    await call('POST', '/api/sync/words', { words: [w('sincefoo', { updatedAt: T0 + 10 * H })] })
    await call('POST', '/api/sync/words', { words: [w('sincebar', { deleted: true, updatedAt: clock })] })
    const next = await json(call('GET', `/api/sync/words?since=${first.body.now}`))
    expect(next.body.words.map((x: any) => [x.word, x.deleted])).toEqual([['sincebar', true]])
    // epoch ms works too
    expect((await json(call('GET', `/api/sync/words?since=${T0 + 12 * H}`))).body.words.length).toBe(1)
  })

  test('import: dedupes within the call and against what is stored (tombstones too), skips bad words', async () => {
    await call('POST', '/api/sync/words', { words: [w('impold', { locale: 'id' }), w('impgone', { locale: 'id', deleted: true })] })
    const r = await json(
      call('POST', '/api/sync/words/import', {
        locale: 'id',
        source: 'swiftkey',
        words: ['impnew', 'impnew', ' impnew2 ', 'impold', 'impgone', '', 'x'.repeat(60), 'bad\nword'],
      }),
    )
    expect(r.body).toEqual({ added: 2, skipped: 6 })
    expect((await find('impnew', 'id'))!).toMatchObject({ source: 'swiftkey', count: 1, deleted: false })
    expect((await find('impnew2', 'id'))).toBeTruthy()
    expect((await find('impgone', 'id'))!.deleted).toBe(true)
    const again = await json(call('POST', '/api/sync/words/import', { locale: 'id', source: 'swiftkey', words: ['impnew', 'impnew2'] }))
    expect(again.body).toEqual({ added: 0, skipped: 2 })
  })

  test('import: 50000 at most, and a big one goes in', async () => {
    const big = Array.from({ length: 50_000 }, (_, i) => `madeup${i}`)
    const r = await json(call('POST', '/api/sync/words/import', { locale: 'zz', source: 'swiftkey', words: big }))
    expect(r.body).toEqual({ added: 50_000, skipped: 0 })
    const tooMany = await json(call('POST', '/api/sync/words/import', { locale: 'zz', source: 'swiftkey', words: [...big, 'one-more'] }))
    expect(tooMany.status).toBe(400)
    const typed = await json(call('POST', '/api/sync/words/import', { locale: 'zz', source: 'typed', words: ['a'] }))
    expect(typed.status).toBe(400)
  })

  test('web search: paginated, most used first, filters, removed list; summary', async () => {
    await call('POST', '/api/sync/words', {
      words: [w('webzap', { locale: 'qq', count: 3 }), w('webzip', { locale: 'qq', count: 8 }), w('WebZop', { locale: 'qq', count: 1, source: 'manual' }), w('webzup', { locale: 'qq', deleted: true })],
    })
    let r = await json(call('GET', '/api/words?q=WEBZ&locale=qq&limit=2'))
    expect(r.body.total).toBe(3)
    expect(r.body.words.map((x: any) => x.word)).toEqual(['webzip', 'webzap'])
    r = await json(call('GET', '/api/words?q=webz&locale=qq&limit=2&offset=2'))
    expect(r.body.words.map((x: any) => x.word)).toEqual(['WebZop'])
    r = await json(call('GET', '/api/words?locale=qq&source=manual'))
    expect(r.body.words.map((x: any) => x.word)).toEqual(['WebZop'])
    r = await json(call('GET', '/api/words?locale=qq&removed=1'))
    expect(r.body.words.map((x: any) => x.word)).toEqual(['webzup'])
    r = await json(call('GET', '/api/words?q=%25'))
    expect(r.body.total).toBe(0) // a % in the search is literal
    expect((await json(call('GET', '/api/words?limit=1000'))).status).toBe(400)
    const s = await json(call('GET', '/api/sync/status'))
    expect(s.body.words.byLocale.qq).toBe(3)
    expect(s.body.words.bySource.manual).toBeGreaterThanOrEqual(1)
    expect(s.body.words.removed).toBeGreaterThanOrEqual(1)
  })
})

describe('words: locales, web writes, spaces', () => {
  test('locales are stored as the language: en_US, en-GB, EN → en; in → id; search by any form', async () => {
    expect(['en_US', 'en-GB', 'EN', 'en', 'in', 'id_ID', 'in-ID', 'pt-BR', 'zh_Hant_TW'].map(canonicalLocale)).toEqual([
      'en', 'en', 'en', 'en', 'id', 'id', 'id', 'pt', 'zh',
    ])
    await call('POST', '/api/sync/words', {
      words: [w('locfrob', { locale: 'en_US', count: 2 }), w('locfrob', { locale: 'en-GB', count: 5 }), w('locfrob', { locale: 'EN', count: 1 })],
    })
    const rows = (await allWords()).filter((x) => x.word === 'locfrob')
    expect(rows.map((x) => [x.locale, x.count])).toEqual([['en', 5]])
    // a phone tombstone (en) now reaches the row an en_US import made
    await call('POST', '/api/sync/words/import', { locale: 'en_US', source: 'swiftkey', words: ['locimport'] })
    clock = T0 + H
    await call('POST', '/api/sync/words', { words: [w('locimport', { locale: 'en', deleted: true, updatedAt: clock })] })
    expect((await allWords()).filter((x) => x.word === 'locimport')).toMatchObject([{ locale: 'en', deleted: true }])
    await call('POST', '/api/sync/words/import', { locale: 'in', source: 'swiftkey', words: ['locindo'] })
    expect((await find('locindo', 'id'))!.source).toBe('swiftkey')
    const r = await json(call('GET', '/api/words?q=locfrob&locale=en_US'))
    expect(r.body.words.map((x: any) => x.locale)).toEqual(['en'])
  })

  test('words with spaces are refused (the phone keeps single words); an import skips them', async () => {
    expect((await call('POST', '/api/sync/words', { words: [w('two words')] })).status).toBe(400)
    expect((await call('POST', '/api/words', { word: 'two words', locale: 'en', deleted: false })).status).toBe(400)
    const r = await json(call('POST', '/api/sync/words/import', { locale: 'en', source: 'swiftkey', words: ['spc one', 'spcsolo'] }))
    expect(r.body).toEqual({ added: 1, skipped: 1 })
  })

  test('web add/remove use the server clock, and say when nothing changed', async () => {
    // the phone's clock was ahead: its add is clamped to the server's now
    clock = T0 + 5 * H
    await call('POST', '/api/sync/words', { words: [w('webclock', { locale: 'en', count: 3, updatedAt: T0 + 9 * H })] })
    clock = T0 + 6 * H
    // the browser's clock is behind: irrelevant now, the server stamps the remove at its own time
    let r = await json(call('POST', '/api/words', { word: 'webclock', locale: 'en_US', deleted: true }))
    expect(r.body).toEqual({ changed: true, locale: 'en' })
    expect(await find('webclock')).toMatchObject({ deleted: true, count: 3, updatedAt: new Date(T0 + 6 * H).toISOString() })
    // removing it again changes nothing, and says so
    r = await json(call('POST', '/api/words', { word: 'webclock', locale: 'en', deleted: true }))
    expect(r.body.changed).toBe(false)
    // restore keeps the source it is given
    clock = T0 + 7 * H
    r = await json(call('POST', '/api/words', { word: 'webclock', locale: 'en', deleted: false, source: 'typed' }))
    expect(r.body.changed).toBe(true)
    expect(await find('webclock')).toMatchObject({ deleted: false, source: 'typed', count: 3 })
    expect((await call('POST', '/api/words', { word: 'webclock', locale: 'en', deleted: false, source: 'stolen' })).status).toBe(400)
    // and the phone's own POST says how many rows changed
    const p = await json(call('POST', '/api/sync/words', { words: [w('webclock', { locale: 'en', count: 1, updatedAt: T0 })] }))
    expect(p.body).toMatchObject({ accepted: 1, changed: 0 })
  })
})

describe('settings', () => {
  test('404 before the first sync; validation; 256 KB limit', async () => {
    expect((await call('GET', '/api/sync/settings')).status).toBe(404)
    expect((await call('PUT', '/api/sync/settings', { prefs: [], updatedAt: T0, deviceId: 'd1' })).status).toBe(400)
    expect((await call('PUT', '/api/sync/settings', { prefs: {}, updatedAt: 'soon', deviceId: 'd1' })).status).toBe(400)
    expect((await call('PUT', '/api/sync/settings', { prefs: {}, updatedAt: T0 })).status).toBe(400)
    const big = await call('PUT', '/api/sync/settings', { prefs: { blob: 'x'.repeat(LIMITS.settingsBytes) }, updatedAt: T0, deviceId: 'd1' })
    expect(big.status).toBe(413)
    const huge = await call('PUT', '/api/sync/settings', { prefs: { blob: 'x'.repeat(LIMITS.settingsBytes * 2) }, updatedAt: T0, deviceId: 'd1' })
    expect(huge.status).toBe(413)
  })

  test('last writer wins per key, by device time; null removes; missing keys stay', async () => {
    let r = await json(
      call('PUT', '/api/sync/settings', { prefs: { theme: 'dark', vibrate: true, size: 3 }, updatedAt: new Date(T0 - 2 * H).toISOString(), deviceId: 'phone-a' }),
    )
    expect(r.body).toEqual({ prefs: { size: 3, theme: 'dark', vibrate: true }, updatedAt: new Date(T0 - 2 * H).toISOString() })
    // phone B changed theme later, and vibrate earlier than A
    r = await json(call('PUT', '/api/sync/settings', { prefs: { theme: 'light', vibrate: false }, updatedAt: T0 - H, deviceId: 'phone-b' }))
    expect(r.body.prefs).toEqual({ size: 3, theme: 'light', vibrate: false })
    r = await json(call('PUT', '/api/sync/settings', { prefs: { theme: 'blue', size: null }, updatedAt: T0 - 3 * H, deviceId: 'phone-a' }))
    expect(r.body.prefs).toEqual({ size: 3, theme: 'light', vibrate: false }) // older on every key: nothing changes
    r = await json(call('PUT', '/api/sync/settings', { prefs: { size: null }, updatedAt: T0, deviceId: 'phone-a' }))
    expect(r.body).toEqual({ prefs: { theme: 'light', vibrate: false }, updatedAt: new Date(T0).toISOString() })
    expect((await json(call('GET', '/api/sync/settings'))).body).toEqual(r.body)
    const s = await json(call('GET', '/api/sync/status'))
    expect(s.body.settings).toMatchObject({ deviceId: 'phone-a', keys: 2 }) // live keys, not the removed one
    await pg.query(`DELETE FROM keyboard_settings`)
  })

  test('an empty type group never hides later keys in it (the reviewer\'s scenario)', async () => {
    // the phone's first sync: all six groups, float and stringSet empty
    const groups = { boolean: { show_hints: true }, int: { delay: 300 }, long: {}, float: {}, string: { theme: 'dark' }, stringSet: {} }
    let r = await json(call('PUT', '/api/sync/settings', { prefs: groups, updatedAt: T0, deviceId: 'phone-a' }))
    expect(r.body.prefs).toEqual({ boolean: { show_hints: true }, int: { delay: 300 }, string: { theme: 'dark' } })
    // later the key font scale (a float) changes: it must reach the server and every other phone
    clock = T0 + H
    r = await json(call('PUT', '/api/sync/settings', { prefs: { ...groups, float: { font_scale: 1.2 } }, updatedAt: T0 + H, deviceId: 'phone-a' }))
    expect(r.body.prefs.float).toEqual({ font_scale: 1.2 })
    expect((await json(call('GET', '/api/sync/settings'))).body.prefs.float).toEqual({ font_scale: 1.2 })
    // an empty group sent later removes nothing; a null removes that key only
    clock = T0 + 2 * H
    r = await json(call('PUT', '/api/sync/settings', { prefs: { float: {}, long: {} }, updatedAt: T0 + 2 * H, deviceId: 'phone-a' }))
    expect(r.body.prefs.float).toEqual({ font_scale: 1.2 })
    r = await json(call('PUT', '/api/sync/settings', { prefs: { float: { font_scale: null }, int: { delay: null } }, updatedAt: T0 + 2 * H, deviceId: 'phone-a' }))
    expect(r.body.prefs).toEqual({ boolean: { show_hints: true }, string: { theme: 'dark' } })
    await pg.query(`DELETE FROM keyboard_settings`)
  })

  test('mergePrefs: empty groups, legacy {} leaves, a value replaced by a group', () => {
    let m = mergePrefs(null, { long: {} }, 10)
    m = mergePrefs(m, { long: { k: 5 } }, 20)
    expect(m.prefs).toEqual({ long: { k: 5 } })
    // a document stored before the fix, with {} as a leaf value
    m = mergePrefs({ prefs: { long: {}, int: { a: 1 } }, keyTimes: { '["long"]': 10, '["int","a"]': 10 } }, { long: { k: 5 } }, 20)
    expect(m.prefs).toEqual({ int: { a: 1 }, long: { k: 5 } })
    expect(Object.keys(m.keyTimes).sort()).toEqual(['["int","a"]', '["long","k"]'])
    // a newer write replaces a value with a group (and back)
    m = mergePrefs(mergePrefs(null, { a: 1 }, 10), { a: { b: 2 } }, 20)
    expect(m.prefs).toEqual({ a: { b: 2 } })
    m = mergePrefs(m, { a: 3 }, 30)
    expect(m.prefs).toEqual({ a: 3 })
    // an older write never wins a structural conflict
    m = mergePrefs(m, { a: { b: 4 } }, 25)
    expect(m.prefs).toEqual({ a: 3 })
  })

  test('HeliBoard-style grouped prefs merge key by key, arrays are values', () => {
    let m = mergePrefs(null, { boolean: { a: true, b: true }, string_set: { s: ['x', 'y'] } }, 10)
    m = mergePrefs(m, { boolean: { b: false }, string_set: { s: ['z'] } }, 20)
    expect(m.prefs).toEqual({ boolean: { a: true, b: false }, string_set: { s: ['z'] } })
    m = mergePrefs(m, { boolean: { a: false } }, 5)
    expect(m.prefs).toEqual({ boolean: { a: true, b: false }, string_set: { s: ['z'] } })
    expect(m.updatedAt).toBe(20)
  })
})

describe('backups', () => {
  const zip = (n: number, seed = 1) => {
    const b = new Uint8Array(n)
    b.set([0x50, 0x4b, 0x03, 0x04])
    for (let i = 4; i < n; i++) b[i] = (i * seed * 2654435761) >>> 24
    return b
  }
  const upload = (body: Uint8Array, kind = 'manual', extra: Record<string, string> = {}) =>
    call('POST', '/api/backups', body, { 'content-type': 'application/zip', 'x-device-id': 'dev-1', 'x-device-name': 'Test Phone', 'x-backup-kind': kind, ...extra })

  afterEach(async () => {
    await pg.query(`DELETE FROM backup`)
  })

  test('upload → list → download round trip, byte for byte, with sha256; delete', async () => {
    const data = zip(300_000, 7)
    const up = await json(upload(data))
    expect(up.status).toBe(200)
    const list = (await json(call('GET', '/api/backups'))).body
    expect(list).toEqual([{ id: up.body.id, createdAt: new Date(T0).toISOString(), deviceName: 'Test Phone', kind: 'manual', sizeBytes: 300_000 }])
    const stored = await pg.query<{ len: number }>(`SELECT length(data) AS len FROM backup`)
    expect(stored.rows[0]!.len).toBeGreaterThan(0)
    const res = await call('GET', `/api/backups/${up.body.id}`)
    expect(res.status).toBe(200)
    expect(res.headers.get('content-type')).toBe('application/zip')
    const back = new Uint8Array(await res.arrayBuffer())
    expect(back).toEqual(data)
    expect(res.headers.get('x-backup-sha256')).toBe(new Bun.CryptoHasher('sha256').update(data).digest('hex'))
    expect((await call('DELETE', `/api/backups/${up.body.id}`)).status).toBe(200)
    expect((await call('GET', `/api/backups/${up.body.id}`)).status).toBe(404)
    expect((await call('DELETE', `/api/backups/${up.body.id}`)).status).toBe(404)
  })

  test('refused: not a zip, empty, wrong type, bad headers, over 20 MB (declared or streamed)', async () => {
    expect((await upload(new Uint8Array([1, 2, 3, 4]))).status).toBe(400)
    expect((await upload(new Uint8Array(0))).status).toBe(400)
    expect((await upload(zip(10), 'sometimes')).status).toBe(400)
    expect((await upload(zip(10), 'auto', { 'x-device-id': '' })).status).toBe(400)
    expect((await upload(zip(10), 'auto', { 'content-type': 'text/plain' })).status).toBe(415)
    expect((await upload(zip(10), 'auto', { 'content-length': String(LIMITS.backupBytes + 1) })).status).toBe(413)
    const tooBig = await json(upload(zip(LIMITS.backupBytes + 1)))
    expect(tooBig.status).toBe(413)
    expect((await json(upload(zip(LIMITS.backupBytes)))).status).toBe(200)
    expect((await pg.query(`SELECT id FROM backup`)).rows.length).toBe(1)
  })

  test('another user cannot see the backup', async () => {
    const up = await json(upload(zip(100)))
    await pg.query(`UPDATE backup SET "userId" = $1 WHERE id = $2`, [userId, up.body.id])
    expect((await call('GET', `/api/backups/not-${up.body.id}`)).status).toBe(404)
  })

  test('retention on insert: newest of 14 days + newest of 8 weeks, Jakarta time', async () => {
    // Three a day for 70 days (10 weeks), oldest first.
    const start = Date.parse('2026-08-01T00:00:00.000Z')
    for (let d = 0; d < 70; d++) {
      for (const hour of [1, 9, 20]) {
        clock = start + d * 24 * H + hour * H
        expect((await upload(zip(64), 'auto')).status).toBe(200)
      }
    }
    const kept = ((await json(call('GET', '/api/backups'))).body as any[]).map((b) => Date.parse(b.createdAt))
    const expected = retained(kept.map((t, i) => ({ id: String(i), createdAt: t })))
    expect(expected.size).toBe(kept.length) // pruning already left only what retention keeps
    // 14 daily (all at 20:00 UTC = 03:00 WIB next day, the newest of each Jakarta day) + older weekly ones
    expect(kept.length).toBeGreaterThanOrEqual(14 + 5)
    expect(kept.length).toBeLessThanOrEqual(14 + 8)
    const days = new Set(kept.map((t) => Math.floor((t + 7 * H) / (24 * H))))
    expect(days.size).toBe(kept.length) // one per day at most
  })

  test('manual backups are kept apart: the newest 10, whatever the day', async () => {
    clock = Date.parse('2026-10-20T01:00:00.000Z')
    const manual: string[] = []
    for (let i = 0; i < 12; i++) {
      clock += 60_000
      manual.push((await json(upload(zip(5000), 'manual'))).body.id)
    }
    // the auto backup an hour later, same day, doesn't replace them
    clock += H
    const auto = (await json(upload(zip(5000), 'auto'))).body.id
    const kept = ((await json(call('GET', '/api/backups'))).body as any[]).map((b) => b.id)
    expect(kept).toEqual([auto, ...manual.slice(2).reverse()])
  })

  test('a much smaller auto backup (an emptied keyboard) prunes nothing, and the last full one is always kept', async () => {
    const start = Date.parse('2026-09-01T03:00:00.000Z')
    for (let d = 0; d < 3; d++) {
      clock = start + d * 24 * H
      await upload(zip(100_000), 'auto')
    }
    const good = ((await json(call('GET', '/api/backups'))).body as any[]).map((b) => b.id)
    // reinstall: same day as the last good one, an almost empty keyboard
    clock = start + 2 * 24 * H + 2 * H
    await upload(zip(500), 'auto')
    let kept = ((await json(call('GET', '/api/backups'))).body as any[]).map((b) => b.id)
    for (const id of good) expect(kept).toContain(id)
    // then 80 days of small backups: every good one would have aged out, but the newest good one stays
    for (let d = 3; d < 83; d++) {
      clock = start + d * 24 * H
      await upload(zip(600), 'auto')
    }
    const list = (await json(call('GET', '/api/backups'))).body as any[]
    kept = list.map((b) => b.id)
    expect(kept).toContain(good[0]) // newest of the good ones
    expect(list.filter((b) => b.sizeBytes === 100_000).length).toBe(1)
    expect(list.length).toBeLessThanOrEqual(14 + 8 + 1)
  })

  test('the upload checksum: a mismatch is refused, a match is stored', async () => {
    const data = zip(2000, 3)
    const sha = new Bun.CryptoHasher('sha256').update(data).digest('hex')
    const bad = await json(upload(data, 'auto', { 'x-backup-sha256': sha.replace(/^./, sha[0] === 'a' ? 'b' : 'a') }))
    expect(bad).toEqual({ status: 400, body: { error: 'checksum_mismatch' } })
    expect((await json(call('GET', '/api/backups'))).body).toEqual([])
    expect((await upload(data, 'auto', { 'x-backup-sha256': sha.toUpperCase() })).status).toBe(200)
  })

  test('retained(): daily and weekly buckets', () => {
    const day = 24 * H
    const base = Date.parse('2026-10-05T00:00:00.000+07:00') // a Monday in Jakarta
    const bs = [
      { id: 'mon-early', createdAt: base + 1 * H },
      { id: 'mon-late', createdAt: base + 23 * H },
      { id: 'prev-week-sun', createdAt: base - 1 * H },
      { id: 'prev-week-wed', createdAt: base - 5 * day },
    ]
    expect([...retained(bs)].sort()).toEqual(['mon-late', 'prev-week-sun', 'prev-week-wed'])
    const many = Array.from({ length: 200 }, (_, i) => ({ id: `d${i}`, createdAt: base - i * day }))
    const keep = retained(many)
    // 14 newest days span 3 weeks (Mon, a full week, 6 days); the 8 newest weeks add 5 older ones
    expect(keep.size).toBe(14 + 5)
  })
})

describe('privacy', () => {
  test('no word, setting value or backup byte reaches the log, even when the database fails', async () => {
    const lines: string[] = []
    const spies = (['log', 'error', 'warn', 'info', 'debug'] as const).map((m) =>
      spyOn(console, m).mockImplementation((...a: unknown[]) => void lines.push(a.map(String).join(' '))),
    )
    try {
      await call('POST', '/api/sync/words', { words: [w('secretfakeword')] })
      await call('POST', '/api/sync/words/import', { locale: 'en', source: 'swiftkey', words: ['secretfakeimport'] })
      await call('PUT', '/api/sync/settings', { prefs: { secretKey: 'secretfakevalue' }, updatedAt: T0, deviceId: 'secretfakedevice' })
      await call('GET', '/api/words?q=secretfakeword')
      const zipBytes = new TextEncoder().encode('PK\x03\x04secretfakebackup')
      await call('POST', '/api/backups', zipBytes, { 'content-type': 'application/zip', 'x-device-id': 'd', 'x-backup-kind': 'auto' })
      // a constraint violation whose Postgres message would quote the row
      await pg.exec(`ALTER TABLE "word" ADD CONSTRAINT no_secret CHECK ("word" <> 'secretfakeboom')`)
      const res = await call('POST', '/api/sync/words', { words: [w('secretfakeboom')] })
      expect(res.status).toBe(500)
      await pg.exec(`ALTER TABLE "word" DROP CONSTRAINT no_secret`)
    } finally {
      for (const s of spies) s.mockRestore()
    }
    expect(lines.some((l) => l.includes('failed'))).toBe(true)
    expect(lines.filter((l) => /secretfake/.test(l))).toEqual([])
    await pg.query(`DELETE FROM backup`)
    await pg.query(`DELETE FROM keyboard_settings`)
  })
})
