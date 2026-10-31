// The Kud Keys Hono app: /api/* (Better Auth, the profile and usage API) and
// the built SPA. Built from its dependencies so tests run it in-process.
// /v1/stream is a WebSocket and lives beside it in server.ts.

import { Hono } from 'hono'
import type { Context, MiddlewareHandler } from 'hono'
import { serveStatic } from 'hono/bun'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { isAllowed } from './lib/allowlist'
import type { Store } from './lib/store'
import { meRouter } from './routes/me'

export type SessionUser = { id: string; email: string; name: string | null }
export type AppEnv = { Variables: { user: SessionUser } }

export interface AppDeps {
  store: Store
  allowlist: Set<string>
  /**
   * The signed-in user for this request (cookie or bearer token), or null when
   * there is no valid session. Throws when the lookup itself failed (database
   * down): that is answered 503, never 401, so the app keeps its token.
   */
  getSession: (headers: Headers) => Promise<SessionUser | null>
  /** Better Auth's handler, mounted at /api/auth/*. */
  authHandler?: (req: Request) => Promise<Response>
  /** "groq:whisper-large-v3", or null when no provider key is set. */
  transcription: string | null
  /** Origins allowed to send cookie writes (BETTER_AUTH_URL + KUDKEYS_ORIGIN). */
  trustedOrigins?: string[]
  /** Built SPA (vite → server/public). Omitted in tests. */
  publicDir?: string
  now?: () => number
}

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

/**
 * CSRF guard for cookie-authenticated writes, as in board: a write from
 * another site is refused before it can ride the session cookie. Requests
 * with neither Sec-Fetch-Site nor Origin (the Android app) pass.
 */
export function isCrossSiteWrite(req: Request, trusted: Set<string>): boolean {
  if (SAFE_METHODS.has(req.method.toUpperCase())) return false
  const site = req.headers.get('sec-fetch-site')
  if (site && site !== 'same-origin' && site !== 'none') return true
  const origin = req.headers.get('origin')
  if (!origin) return false
  if (trusted.has(origin)) return false
  const host = req.headers.get('x-forwarded-host') ?? req.headers.get('host') ?? new URL(req.url).host
  try {
    return new URL(origin).host !== host
  } catch {
    return true
  }
}

const noFraming: MiddlewareHandler = async (c, next) => {
  await next()
  const set = () => {
    c.res.headers.set('X-Frame-Options', 'DENY')
    c.res.headers.set('Content-Security-Policy', "frame-ancestors 'none'")
  }
  try {
    set()
  } catch {
    c.res = new Response(c.res.body, c.res) // auth responses may carry immutable headers
    set()
  }
}

export function createApp(deps: AppDeps) {
  const app = new Hono<AppEnv>()
  app.use('*', noFraming)

  // No database call: Coolify's healthcheck must not fail because Postgres is slow.
  const health = (c: Context) => c.json({ ok: true, transcription: deps.transcription })
  app.get('/api/health', health)
  app.get('/health', health) // older scripts and the first Coolify healthcheck

  if (deps.authHandler) {
    const handler = deps.authHandler
    app.on(['POST', 'GET'], '/api/auth/*', (c) => handler(c.req.raw))
  }

  const trusted = new Set((deps.trustedOrigins ?? []).map((o) => o.replace(/\/+$/, '')))
  const requireUser: MiddlewareHandler<AppEnv> = async (c, next) => {
    if (isCrossSiteWrite(c.req.raw, trusted)) return c.json({ error: 'forbidden', reason: 'cross_site_request' }, 403)
    let user: SessionUser | null
    try {
      user = await deps.getSession(c.req.raw.headers)
    } catch (e) {
      console.error(`[kudkeys] session lookup failed: ${(e as Error).message}`)
      return c.json({ error: 'unavailable', reason: 'session_lookup_failed' }, 503)
    }
    if (!user) return c.json({ error: 'unauthorized' }, 401)
    if (!isAllowed(deps.allowlist, user.email)) return c.json({ error: 'forbidden', reason: 'not_allowed' }, 403)
    c.set('user', user)
    await next()
  }

  app.use('/api/me', requireUser)
  app.use('/api/me/*', requireUser)
  app.route('/api/me', meRouter({ store: deps.store, now: deps.now }))
  app.all('/api/*', (c) => c.json({ error: 'not_found' }, 404))

  if (deps.publicDir) mountSpa(app, deps.publicDir)
  return app
}

/**
 * Serve the built SPA. Hashed assets are immutable; a miss on one is a real
 * 404, never the SPA shell (a CDN would pin text/html under the immutable JS
 * URL). Same policy as board.
 */
function mountSpa(app: Hono<AppEnv>, dir: string) {
  const indexPath = join(dir, 'index.html')
  if (!existsSync(indexPath)) {
    console.warn(`[kudkeys] no SPA build at ${dir} — run \`bun run build\``)
    return
  }
  const indexHtml = readFileSync(indexPath, 'utf8')
  app.use('/assets/*', async (c, next) => {
    await next()
    c.header('Cache-Control', c.res.status === 200 ? 'public, max-age=31536000, immutable' : 'no-store')
  })
  const sendHtml = (c: Context) => {
    c.header('Cache-Control', 'no-cache, must-revalidate')
    return c.html(indexHtml)
  }
  app.get('/', sendHtml)
  app.get('/index.html', sendHtml)
  app.use('*', serveStatic({ root: dir }))
  app.get('/assets/*', (c) => c.text('Not found', 404))
  app.get('*', sendHtml) // client-side routes (react-router)
}
