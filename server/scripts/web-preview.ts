// Runs the web app with no database to set up: the real Hono app, Better Auth
// (memory adapter) and the sync SQL on PGlite (Postgres in WASM, in-process,
// with the real migrations), serving the built SPA (`bun run build` first).
// For looking at pages and taking screenshots; nothing is shared or kept.
//
//   bun server/scripts/web-preview.ts [port]      # default 18190
//
// Signs up preview@kudcrafts.com (password "preview-password-1") and seeds
// made-up words, settings and backups, so every page has something to show.

import { PGlite } from '@electric-sql/pglite'
import { memoryAdapter } from 'better-auth/adapters/memory'
import { readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { createApp } from '../app'
import { createAuth, lookupSession } from '../auth'
import { parseAllowlist } from '../lib/allowlist'
import type { Sql } from '../lib/sql'
import { memoryStore } from '../lib/store'
import { withClientIp } from '../server'

const port = Number(Bun.argv[2] ?? 18190)
const base = `http://localhost:${port}`
const EMAIL = 'preview@kudcrafts.com'
const PASSWORD = 'preview-password-1'

/** Set while seeding, so the backups land on different days. */
let clock: number | null = null
const pg = new PGlite()
const dir = join(import.meta.dir, '../../prisma/migrations')
for (const m of readdirSync(dir).filter((d) => !d.endsWith('.toml')).sort()) await pg.exec(readFileSync(join(dir, m, 'migration.sql'), 'utf8'))
const sql: Sql = async (text, params) => (await pg.query(text, params as any[])).rows as any[]

const authDb: Record<string, any[]> = { kudkeysUser: [], kudkeysSession: [], kudkeysAccount: [], kudkeysVerification: [] }
const allowlist = parseAllowlist(EMAIL)
const auth = createAuth({ database: memoryAdapter(authDb), allowlist, baseURL: base, secret: 'preview-secret-preview-secret-preview' })
const app = createApp({
  store: memoryStore(() => authDb.kudkeysUser as any),
  allowlist,
  getSession: (h) => lookupSession(auth, h),
  authHandler: (r) => auth.handler(r),
  transcription: null,
  sync: { sql },
  trustedOrigins: [base],
  publicDir: resolve(import.meta.dir, '../public'),
  now: () => clock ?? Date.now(),
})
Bun.serve({ port, fetch: (req, server) => app.fetch(withClientIp(req, server.requestIP(req)?.address)) })

// --- seed ---------------------------------------------------------------------------------
const post = (path: string, body: unknown, headers: Record<string, string> = {}) =>
  fetch(base + path, { method: 'POST', headers: { 'content-type': 'application/json', ...headers }, body: JSON.stringify(body) })
await post('/api/auth/sign-up/email', { email: EMAIL, password: PASSWORD, name: 'Preview Person' })
const token = (await post('/api/auth/sign-in/email', { email: EMAIL, password: PASSWORD })).headers.get('set-auth-token')!
const userId = authDb.kudkeysUser![0].id
await pg.query(`INSERT INTO kudkeys_user (id, email, name, "updatedAt") VALUES ($1, $2, 'Preview Person', now())`, [userId, EMAIL])
const bearer = { authorization: `Bearer ${token}` }

const now = Date.now()
const made = (stem: string, n: number) => Array.from({ length: n }, (_, i) => `${stem}${String.fromCharCode(97 + (i % 26))}${Math.floor(i / 26) || ''}`)
await post('/api/sync/words/import', { locale: 'id', source: 'swiftkey', words: made('kelombar', 140) }, bearer)
await post('/api/sync/words/import', { locale: 'en_US', source: 'swiftkey', words: made('frobnick', 90) }, bearer)
const typed = ['glorbix', 'zibbleton', 'plarnish', 'snorfle', 'quibbitz', 'wobbleplume', 'trizzlet', 'blimtak']
await post(
  '/api/sync/words',
  {
    words: [
      ...typed.map((w, i) => ({ word: w, locale: i % 3 ? 'en' : 'id', count: 200 - i * 23, source: 'typed', deleted: false, updatedAt: now - i * 3600_000 })),
      { word: 'Fennoplex', locale: 'en', count: 1, source: 'manual', deleted: false, updatedAt: now - 7200_000 },
      { word: 'grumbleflax', locale: 'en', count: 3, source: 'typed', deleted: true, updatedAt: now - 1800_000 },
    ],
  },
  bearer,
)
await fetch(base + '/api/sync/settings', {
  method: 'PUT',
  headers: { 'content-type': 'application/json', ...bearer },
  body: JSON.stringify({ prefs: { boolean: { show_hints: true }, int: { key_longpress_timeout: 300 }, string: { theme: 'dark' } }, updatedAt: now - 600_000, deviceId: 'preview-phone' }),
})
const zip = (n: number) => {
  const b = new Uint8Array(n)
  b.set([0x50, 0x4b, 0x03, 0x04])
  for (let i = 4; i < n; i++) b[i] = (i * 2654435761) >>> 24
  return b
}
const DAY = 86_400_000
for (const [kind, size, daysAgo] of [['auto', 2_400_000, 4], ['auto', 2_380_000, 3], ['manual', 2_390_000, 2.9], ['auto', 2_410_000, 2], ['auto', 2_430_000, 1]] as const) {
  clock = now - daysAgo * DAY
  await fetch(base + '/api/backups', {
    method: 'POST',
    headers: { ...bearer, 'content-type': 'application/zip', 'x-device-id': 'preview-phone', 'x-device-name': 'Pixel 8', 'x-backup-kind': kind },
    body: zip(size),
  })
}
clock = null
console.log(`[web-preview] ${base} — sign in as ${EMAIL} / ${PASSWORD}`)
