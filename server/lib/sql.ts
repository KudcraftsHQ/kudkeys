// A minimal SQL runner for the sync tables (server/sync/). Production runs it
// through Prisma's raw queries (pgbouncer-safe: no prepared statements are
// kept); tests run the very same statements on PGlite, so the merge rules are
// tested in Postgres itself, not in a stand-in.
//
// Conventions that make the two drivers agree:
// - times go in and out as epoch milliseconds (float8), never as Date;
// - batches go in as one JSON text parameter (jsonb_to_recordset), never as
//   typed arrays;
// - counts are cast to int (no bigint).

import type { PrismaClient } from '@prisma/client'

export type Sql = <T = Record<string, unknown>>(text: string, params?: unknown[]) => Promise<T[]>

export function prismaSql(db: PrismaClient): Sql {
  return (text, params = []) => db.$queryRawUnsafe(text, ...params)
}

/** `to_timestamp` for a timestamp(3) column holding UTC, from a ms parameter. */
export const ts = (param: string) => `(to_timestamp(${param}::float8 / 1000) AT TIME ZONE 'UTC')`
/** A timestamp(3) UTC column as epoch ms. */
export const ms = (col: string) => `(extract(epoch from ${col}) * 1000)::float8`
