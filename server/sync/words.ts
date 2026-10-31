// The words Kud Keys knows the user uses (contract: server/README.md, "Sync").
//
// Merge rule, applied in one INSERT … ON CONFLICT per batch:
// - union by (word, locale);
// - count = max(stored, incoming);
// - deleted follows whichever side has the newer device time; on a tie the
//   delete wins (a removal is never undone by a write from the same instant);
// - updatedAt = max; source stays the first one seen, except when a newer add
//   revives a tombstone (then the reviving source is taken);
// - changedAt (server time, read by ?since=) moves only when something changed.

import { ms, ts, type Sql } from '../lib/sql'

export const SOURCES = ['typed', 'swiftkey', 'manual'] as const
export type Source = (typeof SOURCES)[number]

export type WordRow = { word: string; locale: string; count: number; source: Source; deleted: boolean; updatedAt: number }

const key = (w: { word: string; locale: string }) => `${w.locale}\u0000${w.word}`

/** The same merge as the SQL, for duplicates inside one batch. */
export function mergeRows(a: WordRow, b: WordRow): WordRow {
  const deleted = b.updatedAt > a.updatedAt ? b.deleted : b.updatedAt < a.updatedAt ? a.deleted : a.deleted || b.deleted
  const revived = a.deleted && !deleted
  return {
    word: a.word,
    locale: a.locale,
    count: Math.max(a.count, b.count),
    source: revived ? b.source : a.source,
    deleted,
    updatedAt: Math.max(a.updatedAt, b.updatedAt),
  }
}

export function collapse(rows: WordRow[]): WordRow[] {
  const by = new Map<string, WordRow>()
  for (const r of rows) {
    const k = key(r)
    const prev = by.get(k)
    by.set(k, prev ? mergeRows(prev, r) : r)
  }
  return [...by.values()]
}

const CHUNK = 5000

/** Merge rows into the user's words. Returns how many distinct words were merged. */
export async function mergeWords(sql: Sql, userId: string, rows: WordRow[], now: number): Promise<number> {
  const merged = collapse(rows.map((r) => ({ ...r, updatedAt: Math.min(r.updatedAt, now) })))
  for (let i = 0; i < merged.length; i += CHUNK) {
    const batch = merged.slice(i, i + CHUNK).map((r) => ({ w: r.word, l: r.locale, c: r.count, s: r.source, d: r.deleted, t: r.updatedAt }))
    await sql(
      `INSERT INTO "word" ("userId", "word", "locale", "count", "source", "deleted", "updatedAt", "changedAt")
       SELECT $1, r.w, r.l, r.c, r.s, r.d, ${ts('r.t')}, ${ts('$3')}
       FROM jsonb_to_recordset($2::text::jsonb) AS r(w text, l text, c int, s text, d boolean, t float8)
       ON CONFLICT ("userId", "word", "locale") DO UPDATE SET
         "count" = GREATEST("word"."count", EXCLUDED."count"),
         "deleted" = CASE
           WHEN EXCLUDED."updatedAt" > "word"."updatedAt" THEN EXCLUDED."deleted"
           WHEN EXCLUDED."updatedAt" < "word"."updatedAt" THEN "word"."deleted"
           ELSE "word"."deleted" OR EXCLUDED."deleted" END,
         "source" = CASE
           WHEN "word"."deleted" AND NOT EXCLUDED."deleted" AND EXCLUDED."updatedAt" > "word"."updatedAt" THEN EXCLUDED."source"
           ELSE "word"."source" END,
         "updatedAt" = GREATEST("word"."updatedAt", EXCLUDED."updatedAt"),
         "changedAt" = EXCLUDED."changedAt"
       WHERE EXCLUDED."count" > "word"."count"
          OR EXCLUDED."updatedAt" > "word"."updatedAt"
          OR (EXCLUDED."updatedAt" = "word"."updatedAt" AND EXCLUDED."deleted" AND NOT "word"."deleted")`,
      [userId, JSON.stringify(batch), now],
    )
  }
  return merged.length
}

/** Insert words that are not there yet (a tombstone counts as there). */
export async function importWords(
  sql: Sql,
  userId: string,
  locale: string,
  source: Source,
  words: string[],
  now: number,
): Promise<number> {
  const unique = [...new Set(words)]
  let added = 0
  for (let i = 0; i < unique.length; i += CHUNK) {
    const batch = unique.slice(i, i + CHUNK)
    const rows = await sql<{ n: number }>(
      `WITH ins AS (
         INSERT INTO "word" ("userId", "word", "locale", "count", "source", "deleted", "updatedAt", "changedAt")
         SELECT $1, w, $2, 1, $3, false, ${ts('$5')}, ${ts('$5')}
         FROM jsonb_array_elements_text($4::text::jsonb) AS w
         ON CONFLICT ("userId", "word", "locale") DO NOTHING
         RETURNING 1)
       SELECT count(*)::int AS n FROM ins`,
      [userId, locale, source, JSON.stringify(batch), now],
    )
    added += rows[0]?.n ?? 0
  }
  return added
}

type DbWord = { word: string; locale: string; count: number; source: Source; deleted: boolean; updatedAt: number }
const SELECT = `"word", "locale", "count", "source", "deleted", ${ms('"updatedAt"')} AS "updatedAt"`

/** Every word changed at or after `since` (server time), tombstones included; all when since is null. */
export function changedSince(sql: Sql, userId: string, since: number | null): Promise<DbWord[]> {
  return since === null
    ? sql<DbWord>(`SELECT ${SELECT} FROM "word" WHERE "userId" = $1 ORDER BY "locale", "word"`, [userId])
    : sql<DbWord>(`SELECT ${SELECT} FROM "word" WHERE "userId" = $1 AND "changedAt" >= ${ts('$2')} ORDER BY "locale", "word"`, [
        userId,
        since,
      ])
}

export type WordSearch = { q?: string; locale?: string; source?: Source; removed?: boolean; offset: number; limit: number }

/** For the web app: one page of words, most used first. */
export async function searchWords(sql: Sql, userId: string, s: WordSearch) {
  const where = [`"userId" = $1`, `"deleted" = $2`]
  const params: unknown[] = [userId, !!s.removed]
  if (s.q) {
    params.push(`%${s.q.toLowerCase().replace(/[\\%_]/g, (c) => `\\${c}`)}%`)
    where.push(`lower("word") LIKE $${params.length}`)
  }
  if (s.locale) {
    params.push(s.locale)
    where.push(`"locale" = $${params.length}`)
  }
  if (s.source) {
    params.push(s.source)
    where.push(`"source" = $${params.length}`)
  }
  const w = where.join(' AND ')
  const [total, words] = await Promise.all([
    sql<{ n: number }>(`SELECT count(*)::int AS n FROM "word" WHERE ${w}`, params),
    sql<DbWord>(`SELECT ${SELECT} FROM "word" WHERE ${w} ORDER BY "count" DESC, lower("word"), "word" LIMIT ${s.limit} OFFSET ${s.offset}`, params),
  ])
  return { total: total[0]?.n ?? 0, words }
}

/** Totals by source and by locale (live words), plus removed and the last change. */
export async function wordSummary(sql: Sql, userId: string) {
  const rows = await sql<{ locale: string; source: string; deleted: boolean; n: number; last: number | null }>(
    `SELECT "locale", "source", "deleted", count(*)::int AS n, ${ms('max("changedAt")')} AS last
     FROM "word" WHERE "userId" = $1 GROUP BY 1, 2, 3`,
    [userId],
  )
  const bySource: Record<string, number> = {}
  const byLocale: Record<string, number> = {}
  let total = 0
  let removed = 0
  let last: number | null = null
  for (const r of rows) {
    if (r.last !== null && (last === null || r.last > last)) last = r.last
    if (r.deleted) {
      removed += r.n
      continue
    }
    total += r.n
    bySource[r.source] = (bySource[r.source] ?? 0) + r.n
    byLocale[r.locale] = (byLocale[r.locale] ?? 0) + r.n
  }
  return { total, removed, bySource, byLocale, lastChangedAt: last }
}
