// Better Auth for Kud Keys accounts.
//
// - Email + password. Sign-up is open, but a database hook refuses any email
//   outside KUDKEYS_ALLOWED_EMAILS, and every request re-checks the list
//   (app.ts, and the /v1/stream upgrade in server.ts).
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

export function createAuth(opts: { database: Database; allowlist: Set<string>; baseURL?: string; secret?: string }) {
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
