// The Kud Keys API (server/routes/me.ts; contract in server/README.md).

import { QueryClient } from '@tanstack/react-query'

export type Me = {
  id: string
  email: string
  name: string
  image: string | null
  voiceKeywords: string[]
  languages: string[]
}

export type UsagePeriod = { since: string; sessions: number; audioMinutes: number }
export type VoiceSessionRow = {
  id: string
  session: string
  startedAt: string
  endedAt: string
  audioSeconds: number
  finals: number
  outcome: string
  model: string | null
}
export type Usage = { week: UsagePeriod; month: UsagePeriod; sessions: VoiceSessionRow[] }

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message)
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    credentials: 'include',
    headers: { 'content-type': 'application/json', ...init?.headers },
  })
  const body = await res.json().catch(() => null)
  if (!res.ok) {
    const issues = body?.issues as { path: string; message: string }[] | undefined
    throw new ApiError(res.status, issues?.length ? issues.map((i) => i.message).join('; ') : (body?.error ?? res.statusText))
  }
  return body as T
}

export const api = {
  me: () => call<Me>('/api/me'),
  updateMe: (patch: Partial<Pick<Me, 'name' | 'voiceKeywords' | 'languages'>>) =>
    call<Me>('/api/me', { method: 'PATCH', body: JSON.stringify(patch) }),
  usage: () => call<Usage>('/api/me/usage'),
}

export const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 } },
})

export const ME_KEY = ['me'] as const
export const USAGE_KEY = ['me', 'usage'] as const
export const SESSIONS_KEY = ['sessions'] as const
