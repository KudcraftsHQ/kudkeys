// The keyboard's settings (contract: server/README.md, "Sync").
//
// Last writer wins per key, by the device's `updatedAt`. Keys are leaf paths:
// nested objects are walked, so a flat {key: value} document and HeliBoard's
// grouped {"boolean": {key: value}, …} both merge key by key. A key sent as
// null is removed (and stays removed until a newer write sets it). A key the
// phone leaves out is kept: sending part of the settings never deletes the rest.

import { ms, ts, type Sql } from '../lib/sql'

export type Prefs = Record<string, unknown>
type Flat = Map<string, unknown>

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

export function flatten(prefs: Prefs, prefix: string[] = [], out: Flat = new Map()): Flat {
  for (const [k, v] of Object.entries(prefs)) {
    const path = [...prefix, k]
    if (isObject(v) && Object.keys(v).length > 0) flatten(v, path, out)
    else out.set(JSON.stringify(path), v)
  }
  return out
}

export function unflatten(flat: Flat): Prefs {
  const root: Prefs = {}
  for (const [p, v] of [...flat.entries()].sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))) {
    const path = JSON.parse(p) as string[]
    let node = root
    for (const k of path.slice(0, -1)) {
      if (!isObject(node[k])) node[k] = {}
      node = node[k] as Prefs
    }
    node[path[path.length - 1]!] = v
  }
  return root
}

export type Stored = { prefs: Prefs; keyTimes: Record<string, number>; updatedAt: number; deviceId: string; syncedAt: number; version: number }

/** Merge `incoming` (written at device time `at`) into what is stored. Ties go to the incoming write. */
export function mergePrefs(stored: Pick<Stored, 'prefs' | 'keyTimes'> | null, incoming: Prefs, at: number) {
  const values = stored ? flatten(stored.prefs) : new Map<string, unknown>()
  const times: Record<string, number> = { ...stored?.keyTimes }
  for (const [path, v] of flatten(incoming)) {
    if (path in times && times[path]! > at) continue
    times[path] = at
    if (v === null) values.delete(path)
    else values.set(path, v)
  }
  const updatedAt = Math.max(0, ...Object.values(times))
  return { prefs: unflatten(values), keyTimes: times, updatedAt }
}

const COLS = `"prefs", "keyTimes", ${ms('"updatedAt"')} AS "updatedAt", "deviceId", ${ms('"syncedAt"')} AS "syncedAt", "version"`

export async function getSettings(sql: Sql, userId: string): Promise<Stored | null> {
  const rows = await sql<Stored>(`SELECT ${COLS} FROM "keyboard_settings" WHERE "userId" = $1`, [userId])
  return rows[0] ?? null
}

export class TooLarge extends Error {}

/**
 * Read, merge, write with an optimistic version check, retried on a
 * concurrent write. Throws TooLarge when the merged JSON exceeds maxBytes.
 */
export async function putSettings(
  sql: Sql,
  userId: string,
  incoming: Prefs,
  at: number,
  deviceId: string,
  now: number,
  maxBytes: number,
): Promise<{ prefs: Prefs; updatedAt: number }> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const stored = await getSettings(sql, userId)
    const m = mergePrefs(stored, incoming, at)
    const prefsJson = JSON.stringify(m.prefs)
    if (Buffer.byteLength(prefsJson) > maxBytes) throw new TooLarge()
    const params = [userId, prefsJson, JSON.stringify(m.keyTimes), m.updatedAt, deviceId, now]
    const rows = stored
      ? await sql(
          `UPDATE "keyboard_settings" SET "prefs" = $2::text::jsonb, "keyTimes" = $3::text::jsonb, "updatedAt" = ${ts('$4')},
             "deviceId" = $5, "syncedAt" = ${ts('$6')}, "version" = "version" + 1
           WHERE "userId" = $1 AND "version" = $7::int RETURNING 1 AS ok`,
          [...params, stored.version],
        )
      : await sql(
          `INSERT INTO "keyboard_settings" ("userId", "prefs", "keyTimes", "updatedAt", "deviceId", "syncedAt")
           VALUES ($1, $2::text::jsonb, $3::text::jsonb, ${ts('$4')}, $5, ${ts('$6')})
           ON CONFLICT ("userId") DO NOTHING RETURNING 1 AS ok`,
          params,
        )
    if (rows.length) return { prefs: m.prefs, updatedAt: m.updatedAt }
  }
  throw new Error('settings write kept conflicting')
}
