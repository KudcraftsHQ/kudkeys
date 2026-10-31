// Better Auth for Kud Keys accounts.
//
// - Email + password. Sign-up is open while KUDKEYS_SIGNUP_OPEN is not
//   "false", and even then a database hook refuses any email outside
//   KUDKEYS_ALLOWED_EMAILS. Every request re-checks the list (app.ts, and the
//   /v1/stream upgrade in server.ts). Set KUDKEYS_SIGNUP_OPEN=false once the
//   accounts exist: nobody can sign up after that.
// - Rate limits key on CLIENT_IP_HEADER, which server.ts sets from
//   cf-connecting-ip (or the socket address) and never takes from the client.
//   X-Forwarded-For is ignored: behind Cloudflare its first entry is whatever
//   the client sent.
// - The bearer plugin lets the Android app sign in without cookies: sign-in
//   returns the session token in the `set-auth-token` response header, and
//   `Authorization: Bearer <token>` then works on /api/* and /v1/stream.
// - Sessions last 60 days and roll forward daily while used, so the keyboard
//   stays signed in for as long as someone keeps talking to it.
// - kudkeys_user/kudkeys_session/... tables (prisma/schema.prisma).

import { betterAuth } from 'better-auth'
import { APIError } from 'better-auth/api'
import { bearer } from 'better-auth/plugins/bearer'
import { isAllowed } from './lib/allowlist'

type Database = Parameters<typeof betterAuth>[0]['database']

export const MODEL_NAMES = {
  user: 'kudkeysUser',
  session: 'kudkeysSession',
  account: 'kudkeysAccount',
  verification: 'kudkeysVerification',
} as const

/** BETTER_AUTH_URL plus KUDKEYS_ORIGIN (comma-separated), as origins. */
export function trustedOrigins(env: Record<string, string | undefined> = process.env): string[] {
  const baseURL = env.BETTER_AUTH_URL ?? 'http://localhost:8080'
  return [baseURL, ...(env.KUDKEYS_ORIGIN ?? '').split(',')]
    .map((s) => s.trim().replace(/\/+$/, ''))
    .filter(Boolean)
    .map((s) => {
      try {
        return new URL(s).origin
      } catch {
        return s
      }
    })
}

/** Set by server.ts on every request; a value sent by the client is overwritten. */
export const CLIENT_IP_HEADER = 'x-kudkeys-client-ip'

/** Password sign-ins per client IP per minute. */
export const SIGN_IN_LIMIT = { window: 60, max: 5 }

/** KUDKEYS_SIGNUP_OPEN: on unless set to false/0/no/off. */
export function signUpOpen(env: Record<string, string | undefined> = process.env): boolean {
  return !/^(false|0|no|off)$/i.test((env.KUDKEYS_SIGNUP_OPEN ?? '').trim())
}

/**
 * The session for these headers, or null when there is none (no token, an
 * invalid, expired or revoked one). Anything else — Postgres or pgbouncer
 * down, a timeout — throws, so callers answer 503 instead of "signed out".
 */
export async function lookupSession(
  auth: { api: { getSession: (o: { headers: Headers }) => Promise<{ user: { id: string; email: string; name?: string | null } } | null> } },
  headers: Headers,
) {
  try {
    const s = await auth.api.getSession({ headers })
    return s ? { id: s.user.id, email: s.user.email, name: s.user.name ?? null } : null
  } catch (e) {
    if (e instanceof APIError && e.status === 'UNAUTHORIZED') return null
    throw e
  }
}

export function createAuth(opts: {
  database: Database
  allowlist: Set<string>
  baseURL?: string
  secret?: string
  /** Default: KUDKEYS_SIGNUP_OPEN. */
  signUpOpen?: boolean
}) {
  return betterAuth({
    baseURL: opts.baseURL ?? process.env.BETTER_AUTH_URL ?? 'http://localhost:8080',
    secret: opts.secret ?? process.env.BETTER_AUTH_SECRET,
    basePath: '/api/auth',
    trustedOrigins: trustedOrigins(),
    database: opts.database,

    emailAndPassword: {
      enabled: true,
      minPasswordLength: 10,
      autoSignIn: true,
      disableSignUp: !(opts.signUpOpen ?? signUpOpen()),
    },

    rateLimit: {
      enabled: true,
      storage: 'memory',
      customRules: { '/sign-in/email': SIGN_IN_LIMIT },
    },
    advanced: {
      ipAddress: { ipAddressHeaders: [CLIENT_IP_HEADER] },
    },

    user: { modelName: MODEL_NAMES.user },
    session: {
      modelName: MODEL_NAMES.session,
      expiresIn: 60 * 60 * 24 * 60, // 60 days
      updateAge: 60 * 60 * 24, // roll forward once a day
    },
    account: { modelName: MODEL_NAMES.account },
    verification: { modelName: MODEL_NAMES.verification },

    plugins: [bearer()],

    databaseHooks: {
      user: {
        create: {
          before: async (user) => {
            if (!isAllowed(opts.allowlist, user.email)) {
              throw new APIError('FORBIDDEN', { code: 'EMAIL_NOT_ALLOWED', message: 'This email is not allowed to use Kud Keys' })
            }
            return { data: user }
          },
        },
      },
    },
  })
}

export type KudkeysAuth = ReturnType<typeof createAuth>
