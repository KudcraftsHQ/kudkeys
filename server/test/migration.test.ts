// The migrations apply to a real Postgres (PGlite: Postgres in WASM,
// in-process, nothing shared touched), and voice_session has no column that
// could hold transcript text.

import { expect, test } from 'bun:test'
import { PGlite } from '@electric-sql/pglite'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const dir = join(import.meta.dir, '../../prisma/migrations')

test('migrations apply; profile defaults and usage columns are as the API expects', async () => {
  const pg = new PGlite()
  for (const m of readdirSync(dir).filter((d) => !d.endsWith('.toml')).sort()) {
    await pg.exec(readFileSync(join(dir, m, 'migration.sql'), 'utf8'))
  }
  const tables = await pg.query<{ table_name: string }>(
    `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY 1`,
  )
  expect(tables.rows.map((r) => r.table_name)).toEqual([
    'kudkeys_account',
    'kudkeys_session',
    'kudkeys_user',
    'kudkeys_verification',
    'voice_session',
  ])

  await pg.exec(`INSERT INTO kudkeys_user (id, email, name, "updatedAt") VALUES ('u1', 'a@b.c', 'A', now())`)
  const u = await pg.query<{ voiceKeywords: string[]; languages: string[] }>(`SELECT "voiceKeywords", languages FROM kudkeys_user`)
  expect(u.rows[0]).toEqual({ voiceKeywords: [], languages: ['id', 'en'] })

  const cols = await pg.query<{ column_name: string }>(
    `SELECT column_name FROM information_schema.columns WHERE table_name = 'voice_session' ORDER BY 1`,
  )
  expect(cols.rows.map((r) => r.column_name)).toEqual([
    'audioSeconds',
    'endedAt',
    'finals',
    'id',
    'model',
    'outcome',
    'session',
    'startedAt',
    'userId',
  ])

  // Deleting a user takes their sessions and usage with them.
  await pg.exec(
    `INSERT INTO voice_session (id, "userId", session, "startedAt", "endedAt", "audioSeconds", finals, outcome) VALUES ('v1', 'u1', 'ab12', now(), now(), 1.5, 1, 'stopped')`,
  )
  await pg.exec(`DELETE FROM kudkeys_user WHERE id = 'u1'`)
  expect((await pg.query(`SELECT * FROM voice_session`)).rows.length).toBe(0)
})
