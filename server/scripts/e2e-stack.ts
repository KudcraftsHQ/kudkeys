// The e2e fixture stack: the real Kud Keys server (Hono + Better Auth + Prisma, serving the built web app) on one
// block of localhost ports, against a seeded Postgres database of its own, for the browser suite in e2e/. A test
// fixture, not a dev server: only e2e-queue starts it, and it exits by itself after --ttl or on SIGTERM/SIGINT.
//
//   KUDKEYS_E2E_DATABASE_URL=postgresql://…@127.0.0.1:5432/kudkeys-e2e bun server/scripts/e2e-stack.ts [--ttl 15m]
//
// Ports, from E2E_PORT_BASE (the e2e-queue runner's slot: 4710, 4720, …; 4710 when unset):
//   base     the server: the web app, /api/*, /v1/stream           (APP_URL in e2e/stack.ts)
//   base+1   a fake Groq (transcription + chat), so nothing paid or outbound is ever called
//   base+2   control: GET /status (ready), GET /as/<owner|editor|devices>?next=/path (signs in, then 302 to the app)
//
// Data: the database is <KUDKEYS_E2E_DATABASE_URL's name>-p<base> (kudkeys-e2e-p4710, kudkeys-e2e-p4720…), so two
// stacks never share rows. The stack refuses any host but localhost and any name not starting with kudkeys-e2e (the
// worktree's dev database is a copy of PRODUCTION: never that). It creates the database on first use, brings it up
// with `prisma migrate deploy` (never a reset), truncates it on boot after checking the name again, and seeds:
//   owner@keys.test   words, settings, five backups, voice sessions, a signed-in phone keyboard: the read-only checks
//                     and every phone screen read this user
//   editor@keys.test  the same, for checks that change things (profile save, add/remove a word, delete a backup,
//                     sign a device out), so a write never moves what a read check asserts
//   devices@keys.test the same, signed in only on its phone: the Devices check signs that phone out
//
// Side effects: none leave the machine. Groq (the only outbound integration: transcription and text actions) points
// at the fake on base+1 with a fake key; OpenAI is unset; KUDKEYS_DEV_AUTO_SIGNIN is forced empty. The server gets an
// explicit env and --no-env-file, so the worktree's .env (dev database, real keys) never reaches it.

import { PrismaClient } from '@prisma/client'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'

const REPO = resolve(import.meta.dir, '../..')
const log = (m: string) => console.log(`[e2e-stack] ${m}`)

export const USERS = {
  owner: { email: 'owner@keys.test', password: 'owner-pass-1234', name: 'Owner Person' },
  editor: { email: 'editor@keys.test', password: 'editor-pass-1234', name: 'Editor Person' },
  devices: { email: 'devices@keys.test', password: 'devices-pass-1234', name: 'Devices Person' },
} as const
type Who = keyof typeof USERS
/** The phone keyboard's session the stack signs in for each user (the Devices page names it from this). */
const PHONE_UA = 'KudKeys/0.2 (Android 15; Pixel 8)'
const SEED_UA = 'kudkeys-e2e-seed'

function arg(name: string, dflt: string): string {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1]! : dflt
}
function duration(s: string): number {
  const m = /^(\d+(?:\.\d+)?)(ms|s|m|h)?$/.exec(s.trim())
  if (!m) throw new Error(`bad duration ${s}`)
  return Number(m[1]) * ({ ms: 1, s: 1_000, m: 60_000, h: 3_600_000 }[m[2] ?? 'm'] ?? 60_000)
}

const base = process.env.E2E_PORT_BASE ? Number(process.env.E2E_PORT_BASE) : 4710
if (!Number.isInteger(base) || base < 1024 || base > 65_533) {
  console.error(`[e2e-stack] E2E_PORT_BASE must be a port (1024-65533), got ${process.env.E2E_PORT_BASE}`)
  process.exit(2)
}
const appPort = base
const groqPort = base + 1
const controlPort = base + 2
const ttlMs = duration(arg('ttl', '15m'))
const origin = `http://127.0.0.1:${appPort}`

// ─── the database guard ─────────────────────────────────────────────────────────────────────────────────────────
const LOCAL_HOSTS = new Set(['127.0.0.1', 'localhost', '[::1]', '::1'])
/** Throws unless the URL is a local Postgres database whose name starts with kudkeys-e2e. */
function guard(url: string): URL {
  const u = new URL(url)
  const name = decodeURIComponent(u.pathname.slice(1))
  if (!/^postgres(ql)?:$/.test(u.protocol)) throw new Error(`not a Postgres URL (${u.protocol})`)
  if (!LOCAL_HOSTS.has(u.hostname)) throw new Error(`refusing database host ${u.hostname}: the e2e stack only uses a local Postgres`)
  if (!/^kudkeys-e2e[\w-]*$/.test(name)) throw new Error(`refusing database ${name}: the e2e stack only uses databases named kudkeys-e2e*`)
  return u
}

const rawUrl = process.env.KUDKEYS_E2E_DATABASE_URL
if (!rawUrl) {
  console.error('[e2e-stack] set KUDKEYS_E2E_DATABASE_URL to a local postgres URL named kudkeys-e2e (e2e-queue: --envfile ~/.config/e2e-queue/kudkeys.env)')
  process.exit(2)
}
const baseUrl = guard(rawUrl)
const dbName = `${decodeURIComponent(baseUrl.pathname.slice(1))}-p${appPort}`
const dbUrl = new URL(baseUrl)
dbUrl.pathname = `/${encodeURIComponent(dbName)}`
guard(dbUrl.toString())

// ─── lifecycle ──────────────────────────────────────────────────────────────────────────────────────────────────
let stopping = false
let server: ReturnType<typeof Bun.spawn> | null = null
let builder: ReturnType<typeof Bun.spawn> | null = null
const servers: ReturnType<typeof Bun.serve>[] = []
const box = mkdtempSync(join(process.env.TMPDIR || tmpdir(), 'kudkeys-e2e-'))
async function shutdown(why: string, code = 0) {
  if (stopping) return
  stopping = true
  log(`stopping (${why})`)
  const hard = setTimeout(() => process.exit(code || 1), 10_000)
  for (const s of servers) s.stop(true)
  for (const p of [server, builder]) {
    if (p && p.exitCode === null) {
      p.kill('SIGTERM')
      await Promise.race([p.exited, Bun.sleep(5_000)])
    }
  }
  rmSync(box, { recursive: true, force: true })
  clearTimeout(hard)
  log('stopped')
  process.exit(code)
}
const ttl = setTimeout(() => void shutdown(`ttl ${ttlMs} ms`), ttlMs)
for (const sig of ['SIGTERM', 'SIGINT', 'SIGHUP'] as const) process.on(sig, () => void shutdown(sig))

async function until<T>(fn: () => Promise<T | null | undefined | false>, ms: number, what: string): Promise<T> {
  const end = Date.now() + ms
  let last = ''
  while (Date.now() < end) {
    try {
      const v = await fn()
      if (v) return v
    } catch (e) {
      last = (e as Error).message
    }
    if (server && server.exitCode !== null) throw new Error(`the server exited (${server.exitCode}) before ${what}`)
    await Bun.sleep(200)
  }
  throw new Error(`timed out waiting for ${what}${last ? ` (${last})` : ''}`)
}

/** Create the database if it is missing, then bring it up with prisma migrate deploy. */
async function prepareDb() {
  const admin = new URL(baseUrl)
  admin.pathname = '/postgres'
  const pg = new PrismaClient({ datasourceUrl: admin.toString() })
  try {
    const have = await pg.$queryRawUnsafe<unknown[]>('SELECT 1 FROM pg_database WHERE datname = $1', dbName)
    if (!have.length) {
      try {
        await pg.$executeRawUnsafe(`CREATE DATABASE "${dbName.replaceAll('"', '""')}"`)
        log(`created database ${dbName}`)
      } catch (e) {
        if (!/already exists|42P04/.test(String(e))) throw e
      }
    }
  } finally {
    await pg.$disconnect()
  }
  const url = guard(dbUrl.toString()).toString()
  const m = Bun.spawnSync([join(REPO, 'node_modules/.bin/prisma'), 'migrate', 'deploy'], {
    cwd: REPO,
    env: { PATH: process.env.PATH ?? '', HOME: process.env.HOME ?? '', DATABASE_URL: url, DATABASE_URL_POOLING: url },
  })
  if (m.exitCode !== 0) throw new Error(`prisma migrate deploy on ${dbName}: ${m.stderr.toString()}${m.stdout.toString()}`)
  log(`database ${dbName} migrated`)
  const db = new PrismaClient({ datasourceUrl: url })
  // a fresh slate on every boot, only in the database this stack created and named (checked again here)
  const current = (await db.$queryRawUnsafe<{ current: string }[]>('SELECT current_database() AS current'))[0]?.current
  if (current !== dbName) throw new Error(`connected to ${current}, expected ${dbName}`)
  const tables = await db.$queryRawUnsafe<{ tablename: string }[]>(
    `SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> '_prisma_migrations'`,
  )
  if (tables.length) await db.$executeRawUnsafe(`TRUNCATE ${tables.map((t) => `"${t.tablename}"`).join(', ')} CASCADE`)
  return db
}

/** The web app, built into this stack's own directory (never server/public, which another stack may be reading). */
async function buildSpa(): Promise<string> {
  const out = join(box, 'public')
  const b = Bun.spawn([join(REPO, 'app/node_modules/.bin/vite'), 'build', '--outDir', out, '--emptyOutDir', '--logLevel', 'warn'], {
    cwd: join(REPO, 'app'),
    env: { PATH: process.env.PATH ?? '', HOME: process.env.HOME ?? '', TMPDIR: box, NODE_ENV: 'production' },
    stdout: 'pipe',
    stderr: 'pipe',
  })
  builder = b
  const [o, e, code] = await Promise.all([new Response(b.stdout).text(), new Response(b.stderr).text(), b.exited])
  if (code !== 0) throw new Error(`vite build failed (${code}):\n${o}${e}`)
  log(`web app built into ${out}`)
  return out
}

/** Groq's transcription and chat endpoints, answered locally (the same answers as server/scripts/fake-groq.ts). */
function fakeGroq() {
  servers.push(
    Bun.serve({
      port: groqPort,
      hostname: '127.0.0.1',
      async fetch(req) {
        if (req.method !== 'POST') return new Response('not found', { status: 404 })
        if (new URL(req.url).pathname.endsWith('/chat/completions')) {
          await req.json()
          const content = JSON.stringify({ text: 'Test text.', changed: true, questions: [] })
          return Response.json({ choices: [{ message: { content }, finish_reason: 'stop' }] })
        }
        await req.formData()
        return Response.json({ text: 'test phrase', segments: [{ text: ' test phrase', avg_logprob: -0.1, no_speech_prob: 0.01 }] })
      },
    }),
  )
}

/** The real server, with an env built from nothing: no .env file, no inherited secrets. */
function startServer(publicDir: string) {
  const url = guard(dbUrl.toString()).toString()
  const env: Record<string, string> = {
    PATH: process.env.PATH ?? '',
    HOME: process.env.HOME ?? '',
    TMPDIR: box,
    NODE_ENV: 'test',
    PORT: String(appPort),
    DATABASE_URL: url,
    DATABASE_URL_POOLING: url,
    BETTER_AUTH_SECRET: crypto.randomUUID() + crypto.randomUUID(),
    BETTER_AUTH_URL: origin,
    KUDKEYS_ORIGIN: `http://localhost:${appPort}`,
    KUDKEYS_ALLOWED_EMAILS: Object.values(USERS).map((u) => u.email).join(','),
    KUDKEYS_SIGNUP_OPEN: 'true',
    KUDKEYS_PUBLIC_DIR: publicDir,
    // every outbound call goes to the fake Groq; every other integration is off. Set even when empty, so a .env that
    // Prisma's runtime might load can never fill them in.
    KUDKEYS_PROVIDER: 'groq',
    GROQ_API_KEY: 'gsk-e2e-fake',
    GROQ_TRANSCRIPTIONS_URL: `http://127.0.0.1:${groqPort}/openai/v1/audio/transcriptions`,
    GROQ_CHAT_URL: `http://127.0.0.1:${groqPort}/openai/v1/chat/completions`,
    OPENAI_API_KEY: '',
    OPENAI_REALTIME_URL: `ws://127.0.0.1:${groqPort}/openai-is-off`,
    KUDKEYS_TOKENS: '',
    KUDKEYS_DEV_AUTO_SIGNIN: '',
  }
  server = Bun.spawn([process.execPath, '--no-env-file', join(REPO, 'server/index.ts')], {
    cwd: box,
    env,
    stdout: 'inherit',
    stderr: 'inherit',
  })
  void server.exited.then((c) => {
    if (!stopping) void shutdown(`the server exited (${c})`, 1)
  })
}

// Better Auth rate-limits sign-in to 5 a minute per client IP, which the server takes from cf-connecting-ip. Each
// sign-in the stack makes (seeding, /as/<user>) says it comes from its own address, so a suite can sign in as often as
// it needs while the browser's own form sign-ins (from 127.0.0.1) still meet the real limit.
let ipSeq = 0
const nextIp = () => `10.47.${Math.floor(++ipSeq / 250) % 250}.${(ipSeq % 250) + 1}`

async function signIn(who: Who, userAgent = 'kudkeys-e2e-stack') {
  const u = USERS[who]
  const res = await fetch(`${origin}/api/auth/sign-in/email`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', origin, 'user-agent': userAgent, 'cf-connecting-ip': nextIp() },
    body: JSON.stringify({ email: u.email, password: u.password }),
  })
  if (res.status !== 200) throw new Error(`sign-in ${u.email}: ${res.status} ${await res.text()}`)
  return { token: res.headers.get('set-auth-token')!, cookies: res.headers.getSetCookie() }
}

/** The same made-up data web-preview.ts shows, through the real API, plus voice sessions straight into the table. */
async function seed(db: PrismaClient) {
  const now = Date.now()
  const DAY = 86_400_000
  for (const who of Object.keys(USERS) as Who[]) {
    const u = USERS[who]
    const up = await fetch(`${origin}/api/auth/sign-up/email`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', origin, 'user-agent': SEED_UA, 'cf-connecting-ip': nextIp() },
      body: JSON.stringify({ email: u.email, password: u.password, name: u.name }),
    })
    if (up.status !== 200) throw new Error(`sign-up ${u.email}: ${up.status} ${await up.text()}`)
    // sign-up signs in; that session is the stack's, not a device anyone has, so it goes
    await db.$executeRawUnsafe(`DELETE FROM kudkeys_session WHERE "userAgent" = $1`, SEED_UA)
    // the phone keyboard: its own session, named by its user agent on the Devices page
    const { token } = await signIn(who, PHONE_UA)
    const call = async (method: string, path: string, body: unknown, headers: Record<string, string> = {}) => {
      const res = await fetch(origin + path, {
        method,
        headers: { authorization: `Bearer ${token}`, 'user-agent': PHONE_UA, ...(body instanceof Uint8Array ? {} : { 'content-type': 'application/json' }), ...headers },
        body: body instanceof Uint8Array ? body : JSON.stringify(body),
      })
      if (!res.ok) throw new Error(`${method} ${path} (${u.email}): ${res.status} ${await res.text()}`)
    }
    await call('PATCH', '/api/me', { voiceKeywords: ['Fenno', 'TypeMap'], languages: ['id', 'en'] })
    const made = (stem: string, n: number) => Array.from({ length: n }, (_, i) => `${stem}${String.fromCharCode(97 + (i % 26))}${Math.floor(i / 26) || ''}`)
    await call('POST', '/api/sync/words/import', { locale: 'id', source: 'swiftkey', words: made('kelombar', 140) })
    await call('POST', '/api/sync/words/import', { locale: 'en_US', source: 'swiftkey', words: made('frobnick', 90) })
    const typed = ['glorbix', 'zibbleton', 'plarnish', 'snorfle', 'quibbitz', 'wobbleplume', 'trizzlet', 'blimtak']
    await call('POST', '/api/sync/words', {
      words: [
        ...typed.map((w, i) => ({ word: w, locale: i % 3 ? 'en' : 'id', count: 200 - i * 23, source: 'typed', deleted: false, updatedAt: now - i * 3600_000 })),
        { word: 'Fennoplex', locale: 'en', count: 1, source: 'manual', deleted: false, updatedAt: now - 7200_000 },
        { word: 'grumbleflax', locale: 'en', count: 3, source: 'typed', deleted: true, updatedAt: now - 1800_000 },
      ],
    })
    await call('PUT', '/api/sync/settings', {
      prefs: { boolean: { show_hints: true }, int: { key_longpress_timeout: 300 }, string: { theme: 'dark' } },
      updatedAt: now - 600_000,
      deviceId: 'e2e-phone',
    })
    const zip = (n: number) => {
      const b = new Uint8Array(n)
      b.set([0x50, 0x4b, 0x03, 0x04])
      for (let i = 4; i < n; i++) b[i] = (i * 2654435761) >>> 24
      return b
    }
    // five backups on five days. The API stamps a backup now and keeps one automatic backup a day, so each upload is
    // moved back a day before the next one lands (oldest first: retention only ever compares against what is there).
    for (const [kind, size, daysAgo] of [['auto', 240_000, 4], ['auto', 238_000, 3], ['manual', 239_000, 2.9], ['auto', 241_000, 2], ['auto', 243_000, 1]] as const) {
      await call('POST', '/api/backups', zip(size), { 'content-type': 'application/zip', 'x-device-id': 'e2e-phone', 'x-device-name': 'Pixel 8', 'x-backup-kind': kind })
      await db.$executeRawUnsafe(
        `UPDATE backup SET "createdAt" = $1 WHERE id = (SELECT b.id FROM backup b JOIN kudkeys_user u ON u.id = b."userId" WHERE u.email = $2 ORDER BY b."createdAt" DESC LIMIT 1)`,
        new Date(now - daysAgo * DAY),
        u.email,
      )
    }
    // voice sessions: numbers only, as the stream writes them
    const user = await db.kudkeysUser.findUniqueOrThrow({ where: { email: u.email } })
    await db.voiceSession.createMany({
      data: [
        { hours: 2, secs: 74.5, finals: 6, outcome: 'stopped' },
        { hours: 5, secs: 31.2, finals: 3, outcome: 'idle' },
        { hours: 26, secs: 128.0, finals: 11, outcome: 'stopped' },
        { hours: 50, secs: 12.4, finals: 1, outcome: 'client closed' },
      ].map((s, i) => ({
        userId: user.id,
        session: `e2e${who}${i}`,
        startedAt: new Date(now - s.hours * 3600_000),
        endedAt: new Date(now - s.hours * 3600_000 + s.secs * 1000 + 1500),
        audioSeconds: s.secs,
        finals: s.finals,
        outcome: s.outcome,
        model: 'groq:whisper-large-v3',
      })),
    })
  }
  log(`seeded ${Object.values(USERS).map((u) => u.email).join(', ')}`)
}

function control(db: PrismaClient) {
  const status = () => ({ app: origin, groq: `http://127.0.0.1:${groqPort}`, control: `http://127.0.0.1:${controlPort}`, database: dbName, users: Object.values(USERS).map((u) => u.email), pid: process.pid, ttlMs })
  servers.push(
    Bun.serve({
      port: controlPort,
      hostname: '127.0.0.1',
      async fetch(req) {
        const url = new URL(req.url)
        if (req.method === 'GET' && url.pathname === '/status') return Response.json(status())
        // GET /as/<owner|editor|devices>?next=/path → a fresh Better Auth session for that user, its cookie set on 127.0.0.1
        // (cookies ignore the port), then 302 to the app. Only this stack's control server has it; the app has no such
        // route. The sign-in form itself is covered by its own check.
        const as = /^\/as\/(owner|editor|devices)$/.exec(url.pathname)
        if (req.method === 'GET' && as) {
          const next = url.searchParams.get('next') ?? '/'
          const { cookies } = await signIn(as[1] as Who, req.headers.get('user-agent') ?? 'kudkeys-e2e')
          const headers = new Headers({ location: `${origin}${next.startsWith('/') && !next.startsWith('//') ? next : '/'}`, 'cache-control': 'no-store' })
          for (const c of cookies) headers.append('set-cookie', c)
          return new Response(null, { status: 302, headers })
        }
        if (req.method === 'GET' && url.pathname === '/health') {
          await db.$queryRawUnsafe('SELECT 1')
          return Response.json({ ok: true })
        }
        return new Response('not found', { status: 404 })
      },
    }),
  )
}

try {
  log(`ports app ${appPort}, groq ${groqPort}, control ${controlPort}; database ${dbName}; ttl ${ttlMs} ms`)
  const [db, publicDir] = await Promise.all([prepareDb(), buildSpa()])
  fakeGroq()
  startServer(publicDir)
  await until(async () => (await fetch(`${origin}/api/health`)).ok, 30_000, 'the server health check')
  await seed(db)
  control(db)
  log(`ready ${JSON.stringify({ app: origin, control: `http://127.0.0.1:${controlPort}`, database: dbName })}`)
} catch (e) {
  console.error(`[e2e-stack] failed: ${(e as Error).stack ?? e}`)
  clearTimeout(ttl)
  await shutdown('boot failed', 1)
}
