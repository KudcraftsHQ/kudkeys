// The keyboard's settings (contract: server/README.md, "Sync").
//
// Last writer wins per key, by the device's `updatedAt`. Keys are leaf paths:
// nested objects are walked, so a flat {key: value} document and HeliBoard's
// grouped {"boolean": {key: value}, …} both merge key by key. A key sent as
// null is removed (and stays removed until a newer write sets it). A key the
// phone leaves out is kept: sending part of the settings never deletes the rest.
//
// An empty object is a group with no keys in it, never a value: it adds and
// removes nothing (HeliBoard always sends every type group, often empty). A
// path that is a prefix of another (a value where a group now is, or the other
// way round) is a structural conflict, and the newer write replaces the other.

import { ms, ts, type Sql } from '../lib/sql'

export type Prefs = Record<string, unknown>
type Flat = Map<string, unknown>

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

export function flatten(prefs: Prefs, prefix: string[] = [], out: Flat = new Map()): Flat {
  for (const [k, v] of Object.entries(prefs)) {
    const path = [...prefix, k]
    if (isObject(v)) flatten(v, path, out) // an empty group adds nothing
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

/** True when one path is a prefix of the other (["a"] and ["a","b"]). */
function overlaps(a: string, b: string): boolean {
  // paths are JSON arrays: ["a"] is a prefix of ["a","b"] when '["a"' + ',' starts it
  return a === b || b.startsWith(a.slice(0, -1) + ',') || a.startsWith(b.slice(0, -1) + ',')
}

/** The stored prefs hold an empty object at `path` (written before empty groups were ignored). */
function isGroupLeaf(prefs: Prefs, path: string): boolean {
  let node: unknown = prefs
  for (const k of JSON.parse(path) as string[]) {
    if (!isObject(node)) return false
    node = node[k]
  }
  return isObject(node) && Object.keys(node).length === 0
}

export type Stored = { prefs: Prefs; keyTimes: Record<string, number>; updatedAt: number; deviceId: string; syncedAt: number; version: number }

/** Merge `incoming` (written at device time `at`) into what is stored. Ties go to the incoming write. */
export function mergePrefs(stored: Pick<Stored, 'prefs' | 'keyTimes'> | null, incoming: Prefs, at: number) {
  const values = stored ? flatten(stored.prefs) : new Map<string, unknown>()
  const times: Record<string, number> = {}
  // drop times of paths that hold no value and no tombstone any more (legacy `{}` leaves)
  for (const [p, t] of Object.entries(stored?.keyTimes ?? {})) if (values.has(p) || !isGroupLeaf(stored!.prefs, p)) times[p] = t
  for (const [path, v] of flatten(incoming)) {
    if (Object.entries(times).some(([p, t]) => t > at && overlaps(p, path))) continue // a newer write holds it
    times[path] = at
    if (v === null) values.delete(path)
    else {
      for (const other of [...values.keys()]) if (other !== path && overlaps(other, path)) values.delete(other)
      values.set(path, v)
    }
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
