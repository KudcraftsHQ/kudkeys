// Sync and backups: /api/sync/*, /api/words (web search) and /api/backups.
// Contract in server/README.md ("Sync", "Backups"). Session tokens only (the
// requireUser middleware in app.ts); legacy stream tokens get 401.
//
// Never logged: words, settings values, backup contents. Log lines carry
// counts and sizes only, and errors in these routes are logged by name and
// code, never by message (a Postgres message can quote a row).

import { Hono, type Context } from 'hono'
import { bodyLimit } from 'hono/body-limit'
import { z } from 'zod'
import type { AppEnv } from '../app'
import type { Sql } from '../lib/sql'
import { backupSummary, deleteBackup, getBackupData, insertBackup, listBackups } from '../sync/backups'
import { flatten, getSettings, putSettings, TooLarge } from '../sync/settings'
import { canonicalLocale, changedSince, importWords, mergeWords, searchWords, SOURCES, wordSummary } from '../sync/words'

export const LIMITS = {
  wordsPerCall: 5000,
  importWords: 50_000,
  backupBytes: 20 * 1024 * 1024,
  settingsBytes: 256 * 1024,
  wordLength: 48,
  /** ?since= overlap: `now` is this much earlier than the server clock, so a write still committing is read again next time. */
  sinceOverlapMs: 60_000,
} as const

const iso = (t: number) => new Date(t).toISOString()

/** An ISO time with offset, or epoch milliseconds. */
const time = z
  .union([z.iso.datetime({ offset: true }), z.number().int().nonnegative()])
  .transform((v) => (typeof v === 'number' ? v : Date.parse(v)))

// HeliBoard's dictionary words are at most 48 characters; no control characters or line breaks.
const word = z
  .string()
  .min(1, 'words cannot be empty')
  .max(LIMITS.wordLength, `words are at most ${LIMITS.wordLength} characters`)
  .refine((w) => !/[\p{Cc}\p{Z}\s]/u.test(w), 'words cannot contain spaces, line breaks or control characters (the keyboard keeps single words)')
// Any language tag is accepted and stored as its language (canonicalLocale): en_US → en, in → id.
const locale = z.string().regex(/^[A-Za-z]{2,3}([_-][A-Za-z0-9]{1,8}){0,3}$/, 'locale is a language like "en" or "id"')
const source = z.enum(SOURCES)

const wordRow = z
  .object({
    word,
    locale,
    count: z.number().int().min(0).max(1_000_000_000),
    source,
    deleted: z.boolean(),
    updatedAt: time,
  })
  .strict()

const wordsBody = z.object({ words: z.array(wordRow).max(LIMITS.wordsPerCall, `at most ${LIMITS.wordsPerCall} words per call`) }).strict()

const importBody = z
  .object({
    locale,
    source: z.enum(['swiftkey', 'manual']),
    words: z.array(z.string().max(1000)).max(LIMITS.importWords, `at most ${LIMITS.importWords} words per import`),
  })
  .strict()

const settingsBody = z
  .object({
    prefs: z.record(z.string(), z.unknown()),
    updatedAt: time,
    deviceId: z.string().min(1).max(200),
  })
  .strict()

// The web app's add / remove / restore: one word, stamped with the server's clock (not the browser's).
const webWordBody = z.object({ word, locale, deleted: z.boolean(), source: source.optional() }).strict()

const searchQuery = z.object({
  q: z.string().max(100).optional(),
  locale: locale.optional(),
  source: source.optional(),
  removed: z.enum(['0', '1', 'true', 'false']).optional(),
  offset: z.coerce.number().int().min(0).max(1_000_000).default(0),
  limit: z.coerce.number().int().min(1).max(200).default(50),
})

const backupHeaders = z.object({
  deviceId: z.string().min(1, 'X-Device-Id is required').max(200),
  deviceName: z.string().max(200),
  kind: z.enum(['auto', 'manual'], 'X-Backup-Kind is auto or manual'),
})

type Issue = { path: PropertyKey[]; message: string }
const invalid = (c: Context, issues: Issue[]) =>
  c.json({ error: 'invalid', issues: issues.map((i) => ({ path: i.path.map(String).join('.'), message: i.message })) }, 400)

const limit = (maxSize: number) =>
  bodyLimit({ maxSize, onError: (c) => c.json({ error: 'too_large', maxBytes: maxSize }, 413) })

async function readJson(c: Context): Promise<unknown> {
  return c.req.json().catch(() => undefined)
}

export function syncRouter(deps: { sql: Sql; now?: () => number }) {
  const { sql } = deps
  const now = () => deps.now?.() ?? Date.now()
  const r = new Hono<AppEnv>()

  // ── Settings ────────────────────────────────────────────────────────
  r.get('/sync/settings', async (c) => {
    const s = await getSettings(sql, c.get('user').id)
    if (!s) return c.json({ error: 'not_found' }, 404)
    return c.json({ prefs: s.prefs, updatedAt: iso(s.updatedAt) })
  })

  r.put('/sync/settings', limit(LIMITS.settingsBytes + 64 * 1024), async (c) => {
    const parsed = settingsBody.safeParse(await readJson(c))
    if (!parsed.success) return invalid(c, parsed.error.issues)
    const { prefs, updatedAt, deviceId } = parsed.data
    if (Buffer.byteLength(JSON.stringify(prefs)) > LIMITS.settingsBytes) return c.json({ error: 'too_large', maxBytes: LIMITS.settingsBytes }, 413)
    const t = now()
    try {
      const m = await putSettings(sql, c.get('user').id, prefs, Math.min(updatedAt, t), deviceId, t, LIMITS.settingsBytes)
      return c.json({ prefs: m.prefs, updatedAt: iso(m.updatedAt) })
    } catch (e) {
      if (e instanceof TooLarge) return c.json({ error: 'too_large', maxBytes: LIMITS.settingsBytes }, 413)
      throw e
    }
  })

  // ── Words ───────────────────────────────────────────────────────────
  r.get('/sync/words', async (c) => {
    const raw = c.req.query('since')
    let since: number | null = null
    if (raw !== undefined && raw !== '') {
      const p = time.safeParse(/^\d+$/.test(raw) ? Number(raw) : raw)
      if (!p.success) return invalid(c, [{ path: ['since'], message: 'since is an ISO time (the `now` of the last call)' }])
      since = p.data
    }
    const t = now()
    const words = await changedSince(sql, c.get('user').id, since)
    return c.json({
      words: words.map((w) => ({ ...w, updatedAt: iso(w.updatedAt) })),
      now: iso(t - LIMITS.sinceOverlapMs),
    })
  })

  r.post('/sync/words', limit(4 * 1024 * 1024), async (c) => {
    const parsed = wordsBody.safeParse(await readJson(c))
    if (!parsed.success) return invalid(c, parsed.error.issues.slice(0, 20))
    const t = now()
    const { accepted, changed } = await mergeWords(sql, c.get('user').id, parsed.data.words, t)
    return c.json({ accepted, changed, now: iso(t - LIMITS.sinceOverlapMs) })
  })

  r.post('/sync/words/import', limit(8 * 1024 * 1024), async (c) => {
    const parsed = importBody.safeParse(await readJson(c))
    if (!parsed.success) return invalid(c, parsed.error.issues.slice(0, 20))
    const { locale: loc, source: src, words } = parsed.data
    // Imports are forgiving: each word is trimmed, and one that isn't a valid word is skipped, not fatal.
    const valid = words.map((w) => w.trim()).filter((w) => word.safeParse(w).success)
    const added = await importWords(sql, c.get('user').id, loc, src, valid, now())
    console.log(`[kudkeys] words import: ${words.length} sent, ${added} added`)
    return c.json({ added, skipped: words.length - added })
  })

  r.get('/sync/status', async (c) => {
    const userId = c.get('user').id
    const [s, words, backups] = await Promise.all([getSettings(sql, userId), wordSummary(sql, userId), backupSummary(sql, userId)])
    return c.json({
      settings: s
        ? { updatedAt: iso(s.updatedAt), syncedAt: iso(s.syncedAt), deviceId: s.deviceId, keys: flatten(s.prefs).size }
        : null,
      words: { ...words, lastChangedAt: words.lastChangedAt === null ? null : iso(words.lastChangedAt) },
      backups: { ...backups, latestAt: backups.latestAt === null ? null : iso(backups.latestAt) },
    })
  })

  // For the web app's Words page. Add and remove go through POST /api/sync/words.
  r.get('/words', async (c) => {
    const parsed = searchQuery.safeParse(c.req.query())
    if (!parsed.success) return invalid(c, parsed.error.issues)
    const { removed, ...q } = parsed.data
    const res = await searchWords(sql, c.get('user').id, { ...q, q: q.q?.trim() || undefined, removed: removed === '1' || removed === 'true' })
    return c.json({
      total: res.total,
      offset: q.offset,
      limit: q.limit,
      words: res.words.map((w) => ({ ...w, updatedAt: iso(w.updatedAt) })),
    })
  })

  r.post('/words', limit(16 * 1024), async (c) => {
    const parsed = webWordBody.safeParse(await readJson(c))
    if (!parsed.success) return invalid(c, parsed.error.issues)
    const { word: wd, locale: loc, deleted, source: src } = parsed.data
    const t = now()
    const row = { word: wd, locale: loc, count: deleted ? 0 : 1, source: src ?? ('manual' as const), deleted, updatedAt: t }
    const { changed } = await mergeWords(sql, c.get('user').id, [row], t)
    return c.json({ changed: changed > 0, locale: canonicalLocale(loc) })
  })

  // ── Backups ─────────────────────────────────────────────────────────
  r.get('/backups', async (c) => {
    const list = await listBackups(sql, c.get('user').id)
    return c.json(list.map((b) => ({ id: b.id, createdAt: iso(b.createdAt), deviceName: b.deviceName, kind: b.kind, sizeBytes: b.sizeBytes })))
  })

  r.post('/backups', async (c) => {
    const h = backupHeaders.safeParse({
      deviceId: c.req.header('x-device-id') ?? '',
      deviceName: c.req.header('x-device-name') ?? '',
      kind: c.req.header('x-backup-kind'),
    })
    if (!h.success) return invalid(c, h.error.issues)
    const type = (c.req.header('content-type') ?? '').split(';')[0]!.trim().toLowerCase()
    if (!['application/zip', 'application/octet-stream', 'application/x-zip-compressed'].includes(type)) {
      return c.json({ error: 'unsupported_media_type', expected: 'application/zip' }, 415)
    }
    const declared = Number(c.req.header('content-length') ?? NaN)
    if (declared > LIMITS.backupBytes) return c.json({ error: 'too_large', maxBytes: LIMITS.backupBytes }, 413)
    const body = c.req.raw.body
    if (!body) return invalid(c, [{ path: ['body'], message: 'the backup zip is the request body' }])

    // One pass over the upload: size check, sha256 and gzip as the bytes arrive,
    // so only the compressed copy is held in memory.
    const hasher = new Bun.CryptoHasher('sha256')
    const gzip = new CompressionStream('gzip')
    const writer = gzip.writable.getWriter()
    const compressed = new Response(gzip.readable).arrayBuffer()
    let size = 0
    let head = new Uint8Array(0)
    const reader = body.getReader()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > LIMITS.backupBytes) {
        await reader.cancel().catch(() => {})
        await writer.abort().catch(() => {})
        await compressed.catch(() => {})
        return c.json({ error: 'too_large', maxBytes: LIMITS.backupBytes }, 413)
      }
      if (head.byteLength < 4) head = new Uint8Array([...head, ...value.subarray(0, 4 - head.byteLength)])
      hasher.update(value)
      await writer.write(value)
    }
    await writer.close()
    const data = new Uint8Array(await compressed)
    // Every zip, empty ones included, starts with "PK".
    if (size === 0 || head[0] !== 0x50 || head[1] !== 0x4b) {
      return invalid(c, [{ path: ['body'], message: 'the body is not a zip file' }])
    }
    const sha256 = hasher.digest('hex')
    // The phone sends the zip's sha256: a truncated or corrupted upload is refused, not stored as a good backup.
    const expected = c.req.header('x-backup-sha256')?.trim().toLowerCase()
    if (expected && expected !== sha256) return c.json({ error: 'checksum_mismatch' }, 400)
    const { id, pruned } = await insertBackup(sql, c.get('user').id, {
      createdAt: now(),
      ...h.data,
      sizeBytes: size,
      sha256,
      data,
    })
    console.log(`[kudkeys] backup stored: ${size} bytes (${data.byteLength} gzip), ${pruned} pruned`)
    return c.json({ id })
  })

  r.get('/backups/:id', async (c) => {
    const b = await getBackupData(sql, c.get('user').id, c.req.param('id'))
    if (!b) return c.json({ error: 'not_found' }, 404)
    const day = new Date(b.createdAt + 7 * 3600_000).toISOString().slice(0, 16).replace(/[T:]/g, '-')
    const zip = new Blob([b.data as Uint8Array<ArrayBuffer>]).stream().pipeThrough(new DecompressionStream('gzip'))
    return new Response(zip, {
      headers: {
        'content-type': 'application/zip',
        'content-length': String(b.sizeBytes),
        'content-disposition': `attachment; filename="kudkeys-backup-${day}.zip"`,
        'cache-control': 'private, no-store',
        'x-backup-sha256': b.sha256,
        etag: `"${b.sha256}"`,
      },
    })
  })

  r.delete('/backups/:id', async (c) => {
    if (!(await deleteBackup(sql, c.get('user').id, c.req.param('id')))) return c.json({ error: 'not_found' }, 404)
    return c.json({ ok: true })
  })

  return r
}
