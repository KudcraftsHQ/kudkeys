// Better Auth React client. The handler is mounted at /api/auth/* (server/app.ts);
// in dev, Vite proxies /api to the server, so relative URLs work.

import { createAuthClient } from 'better-auth/react'

export const authClient = createAuthClient({
  baseURL: typeof window !== 'undefined' ? window.location.origin : '',
  basePath: '/api/auth',
})

export const { useSession, signIn, signUp, signOut } = authClient
