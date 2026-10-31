// The Kud Keys API (server/routes/me.ts, server/routes/sync.ts; contract in server/README.md).

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

export type WordSource = 'typed' | 'swiftkey' | 'manual'
export type Word = { word: string; locale: string; count: number; source: WordSource; deleted: boolean; updatedAt: string }
export type WordPage = { total: number; offset: number; limit: number; words: Word[] }
export type WordSearch = { q?: string; locale?: string; source?: WordSource; removed?: boolean; offset: number; limit: number }
export type SyncStatus = {
  settings: { updatedAt: string; syncedAt: string; deviceId: string; keys: number } | null
  words: { total: number; removed: number; bySource: Record<string, number>; byLocale: Record<string, number>; lastChangedAt: string | null }
  backups: { count: number; totalBytes: number; latestAt: string | null }
}
export type BackupRow = { id: string; createdAt: string; deviceName: string; kind: 'auto' | 'manual'; sizeBytes: number }

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
  syncStatus: () => call<SyncStatus>('/api/sync/status'),
  words: (s: WordSearch) => {
    const q = new URLSearchParams({ offset: String(s.offset), limit: String(s.limit) })
    if (s.q) q.set('q', s.q)
    if (s.locale) q.set('locale', s.locale)
    if (s.source) q.set('source', s.source)
    if (s.removed) q.set('removed', '1')
    return call<WordPage>(`/api/words?${q}`)
  },
  /** Add (deleted: false) or remove (deleted: true, a tombstone) one word, as of now. */
  putWord: (w: Pick<Word, 'word' | 'locale' | 'count' | 'source' | 'deleted'>) =>
    call<{ accepted: number }>('/api/sync/words', {
      method: 'POST',
      body: JSON.stringify({ words: [{ ...w, updatedAt: new Date().toISOString() }] }),
    }),
  backups: () => call<BackupRow[]>('/api/backups'),
  deleteBackup: (id: string) => call<{ ok: true }>(`/api/backups/${encodeURIComponent(id)}`, { method: 'DELETE' }),
}

export const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 } },
})

export const ME_KEY = ['me'] as const
export const USAGE_KEY = ['me', 'usage'] as const
export const SESSIONS_KEY = ['sessions'] as const
export const SYNC_STATUS_KEY = ['sync', 'status'] as const
export const WORDS_KEY = ['words'] as const
export const BACKUPS_KEY = ['backups'] as const
