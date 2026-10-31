// Kud Keys server: Hono on Bun. /api/* (accounts, profile, usage), the built
// web app, and the /v1/stream voice WebSocket, from one process.

import { resolve } from 'node:path'
import { prismaAdapter } from 'better-auth/adapters/prisma'
import { createApp } from './app'
import { createAuth, lookupSession, signUpOpen, trustedOrigins } from './auth'
import { parseAllowlist } from './lib/allowlist'
import { db } from './lib/db'
import { prismaStore } from './lib/store'
import { createServer, streamAuthenticator } from './server'
import { createTextEngine } from './text/engine'
import { activeModel, loadConfig } from './voice/config'

if (!process.env.BETTER_AUTH_SECRET) {
  throw new Error('BETTER_AUTH_SECRET is not set. Generate one with: openssl rand -base64 32')
}
const cfg = loadConfig()
const allowlist = parseAllowlist(process.env.KUDKEYS_ALLOWED_EMAILS)
if (allowlist.size === 0) console.warn('[kudkeys] KUDKEYS_ALLOWED_EMAILS is empty — nobody can sign up or sign in')
if (!signUpOpen()) console.log('[kudkeys] KUDKEYS_SIGNUP_OPEN=false — sign-up is closed')
if (cfg.tokens.length === 0) console.warn('[kudkeys] KUDKEYS_TOKENS is empty — /v1/stream accepts account sessions only')

const auth = createAuth({ database: prismaAdapter(db, { provider: 'postgresql' }), allowlist })
const store = prismaStore(db)
const getSession = (headers: Headers) => lookupSession(auth, headers)
const model = activeModel(cfg)
const textEngine = cfg.text.apiKey ? createTextEngine({ apiKey: cfg.text.apiKey, url: cfg.text.url, model: cfg.text.model, tidyModel: cfg.text.tidyModel }) : null

const app = createApp({
  store,
  allowlist,
  getSession,
  authHandler: (req) => auth.handler(req),
  transcription: model,
  text: { engine: textEngine, perMinute: cfg.text.perMinute },
  trustedOrigins: trustedOrigins(),
  publicDir: process.env.KUDKEYS_PUBLIC_DIR ?? resolve(import.meta.dir, 'public'),
})

const server = createServer(cfg, {
  app,
  store,
  authenticate: streamAuthenticator({ tokens: cfg.tokens, getSession, store, allowlist }),
})
const keyVar = cfg.provider === 'groq' ? 'GROQ_API_KEY' : 'OPENAI_API_KEY'
console.log(
  `[kudkeys] listening on :${server.port} — transcription: ${model ?? `not configured (set ${keyVar})`} — text: ${textEngine ? `groq:${textEngine.models.fix} (tidy: ${textEngine.models.tidy})` : 'not configured (set GROQ_API_KEY)'}`,
)
