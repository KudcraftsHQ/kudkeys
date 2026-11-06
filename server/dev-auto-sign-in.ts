// Dev only: sign every browser in as one user, so a conductor worktree's web app opens straight away.
//
//   KUDKEYS_DEV_AUTO_SIGNIN=<email>   written into the worktree's .env by scripts/conductor-setup.sh
//
// The signed-out web app (in a Vite dev build, when the same variable is set) sends the browser to
// GET /api/auth/dev/auto-sign-in?next=<path>, which creates a normal Better Auth session for that user,
// sets its cookie and redirects back. Refused outright under NODE_ENV=production: the server won't start.
import type { BetterAuthPlugin } from 'better-auth'
import { createAuthEndpoint } from 'better-auth/api'
import { setSessionCookie } from 'better-auth/cookies'

export const DEV_AUTO_SIGN_IN_PATH = '/dev/auto-sign-in'

/** The email to sign everyone in as, or null. Throws in production. */
export function devAutoSignInEmail(env: Record<string, string | undefined> = process.env): string | null {
  const email = env.KUDKEYS_DEV_AUTO_SIGNIN?.trim()
  if (!email) return null
  if (env.NODE_ENV === 'production') throw new Error('KUDKEYS_DEV_AUTO_SIGNIN must never be set in production')
  return email
}

/** A same-origin path to land on (never another site, never back to /login). */
const safePath = (next: string | null) =>
  next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\') && !next.startsWith('/login') ? next : '/'

export function devAutoSignIn(email: string): BetterAuthPlugin {
  return {
    id: 'kudkeys-dev-auto-sign-in',
    endpoints: {
      devAutoSignIn: createAuthEndpoint(DEV_AUTO_SIGN_IN_PATH, { method: 'GET' }, async (ctx) => {
        const found = await ctx.context.internalAdapter.findUserByEmail(email)
        if (!found) throw new Error(`KUDKEYS_DEV_AUTO_SIGNIN: no user ${email} in this database`)
        const session = await ctx.context.internalAdapter.createSession(found.user.id)
        await setSessionCookie(ctx, { session, user: found.user })
        const next = ctx.request ? new URL(ctx.request.url).searchParams.get('next') : null
        throw ctx.redirect(safePath(next))
      }),
    },
  }
}
