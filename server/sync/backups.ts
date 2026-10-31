// Backups: the phone's HeliBoard backup zip, stored gzip-compressed in
// Postgres (contract: server/README.md, "Backups").
//
// Retention per user, pruned whenever a backup is added (restic's
// --keep-daily 14 --keep-weekly 8): of the 14 most recent Jakarta days that
// have a backup, the newest backup of each day is kept; likewise the newest of
// each of the 8 most recent weeks (Monday to Sunday) that have one. A backup
// can count for both. Everything else is deleted. Counting days that *have*
// backups, not calendar days, means a phone that was off for a month never
// loses its last backups.

import { ms, ts, type Sql } from '../lib/sql'

export const KEEP_DAILY = 14
export const KEEP_WEEKLY = 8
const WIB_OFFSET_MS = 7 * 3600_000
const DAY_MS = 86_400_000

export type BackupMeta = { id: string; createdAt: number; deviceId: string; deviceName: string; kind: 'auto' | 'manual'; sizeBytes: number; sha256: string }

const jakartaDay = (t: number) => Math.floor((t + WIB_OFFSET_MS) / DAY_MS)
// 1970-01-01 was a Thursday; +3 puts week boundaries on Mondays.
const jakartaWeek = (t: number) => Math.floor((jakartaDay(t) + 3) / 7)

/** Ids to keep, from all of a user's backups. */
export function retained(backups: { id: string; createdAt: number }[]): Set<string> {
  const newestFirst = [...backups].sort((a, b) => b.createdAt - a.createdAt || (a.id < b.id ? 1 : -1))
  const keep = new Set<string>()
  for (const [bucket, limit] of [
    [jakartaDay, KEEP_DAILY],
    [jakartaWeek, KEEP_WEEKLY],
  ] as const) {
    const seen = new Set<number>()
    for (const b of newestFirst) {
      const k = bucket(b.createdAt)
      if (seen.has(k)) continue
      if (seen.size >= limit) break
      seen.add(k)
      keep.add(b.id)
    }
  }
  return keep
}

const META = `"id", ${ms('"createdAt"')} AS "createdAt", "deviceId", "deviceName", "kind", "sizeBytes", "sha256"`

export function listBackups(sql: Sql, userId: string): Promise<BackupMeta[]> {
  return sql<BackupMeta>(`SELECT ${META} FROM "backup" WHERE "userId" = $1 ORDER BY "createdAt" DESC, "id" DESC`, [userId])
}

export async function insertBackup(
  sql: Sql,
  userId: string,
  b: Omit<BackupMeta, 'id'> & { data: Uint8Array },
): Promise<{ id: string; pruned: number }> {
  const id = crypto.randomUUID()
  await sql(
    `INSERT INTO "backup" ("id", "userId", "createdAt", "deviceId", "deviceName", "kind", "sizeBytes", "sha256", "data")
     VALUES ($1, $2, ${ts('$3')}, $4, $5, $6, $7::int, $8, $9::bytea)`,
    [id, userId, b.createdAt, b.deviceId, b.deviceName, b.kind, b.sizeBytes, b.sha256, Buffer.from(b.data.buffer, b.data.byteOffset, b.data.byteLength)],
  )
  return { id, pruned: await prune(sql, userId) }
}

export async function prune(sql: Sql, userId: string): Promise<number> {
  const all = await sql<{ id: string; createdAt: number }>(
    `SELECT "id", ${ms('"createdAt"')} AS "createdAt" FROM "backup" WHERE "userId" = $1`,
    [userId],
  )
  const keep = retained(all)
  const drop = all.filter((b) => !keep.has(b.id)).map((b) => b.id)
  if (drop.length === 0) return 0
  await sql(`DELETE FROM "backup" WHERE "userId" = $1 AND "id" IN (SELECT jsonb_array_elements_text($2::text::jsonb))`, [
    userId,
    JSON.stringify(drop),
  ])
  return drop.length
}

export async function getBackupData(sql: Sql, userId: string, id: string) {
  const rows = await sql<BackupMeta & { data: Uint8Array }>(`SELECT ${META}, "data" FROM "backup" WHERE "userId" = $1 AND "id" = $2`, [userId, id])
  return rows[0] ?? null
}

export async function deleteBackup(sql: Sql, userId: string, id: string): Promise<boolean> {
  const rows = await sql(`DELETE FROM "backup" WHERE "userId" = $1 AND "id" = $2 RETURNING 1 AS ok`, [userId, id])
  return rows.length > 0
}

export async function backupSummary(sql: Sql, userId: string) {
  const rows = await sql<{ n: number; bytes: number; latest: number | null }>(
    `SELECT count(*)::int AS n, coalesce(sum("sizeBytes"), 0)::float8 AS bytes, ${ms('max("createdAt")')} AS latest FROM "backup" WHERE "userId" = $1`,
    [userId],
  )
  const r = rows[0]
  return { count: r?.n ?? 0, totalBytes: r?.bytes ?? 0, latestAt: r?.latest ?? null }
}
