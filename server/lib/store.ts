// Everything the server reads and writes besides Better Auth's own tables:
// the voice profile on the user row, the per-stream usage rows and the
// per-call text usage rows. One
// interface, two implementations — Postgres through Prisma, and memory for tests.
//
// No method takes or returns transcript or text, and neither usage table has
// a column that could hold it.

import type { PrismaClient } from '@prisma/client'
import type { SessionSummary } from '../voice/session'

export const DEFAULT_LANGUAGES = ['id', 'en']

export type Profile = {
  id: string
  email: string
  name: string
  image: string | null
  voiceKeywords: string[]
  languages: string[]
}

export type ProfilePatch = Partial<Pick<Profile, 'name' | 'image' | 'voiceKeywords' | 'languages'>>

export type VoiceSessionRow = {
  id: string
  session: string
  startedAt: Date
  endedAt: Date
  audioSeconds: number
  finals: number
  outcome: string
  model: string | null
}

/** One /api/text/* call. Counts only; null counts for private fields. */
export type TextUsageRow = {
  kind: 'tidy' | 'fix' | 'instruct'
  charsIn: number | null
  charsOut: number | null
  ms: number
  model: string
  ok: boolean
}

export type UsageTotal = { sessions: number; audioSeconds: number }

export interface Store {
  getProfile(userId: string): Promise<Profile | null>
  updateProfile(userId: string, patch: ProfilePatch): Promise<Profile | null>
  recordVoiceSession(userId: string, s: SessionSummary): Promise<void>
  /** Newest first. */
  recentVoiceSessions(userId: string, limit: number): Promise<VoiceSessionRow[]>
  usageSince(userId: string, since: Date): Promise<UsageTotal>
  recordTextUsage(userId: string, row: TextUsageRow): Promise<void>
}

const PROFILE_SELECT = { id: true, email: true, name: true, image: true, voiceKeywords: true, languages: true } as const
const SESSION_SELECT = {
  id: true,
  session: true,
  startedAt: true,
  endedAt: true,
  audioSeconds: true,
  finals: true,
  outcome: true,
  model: true,
} as const

export function prismaStore(db: PrismaClient): Store {
  return {
    getProfile: (userId) => db.kudkeysUser.findUnique({ where: { id: userId }, select: PROFILE_SELECT }),
    async updateProfile(userId, patch) {
      try {
        return await db.kudkeysUser.update({ where: { id: userId }, data: patch, select: PROFILE_SELECT })
      } catch (e) {
        if ((e as { code?: string }).code === 'P2025') return null // no such user
        throw e
      }
    },
    async recordVoiceSession(userId, s) {
      await db.voiceSession.create({ data: { userId, ...s } })
    },
    recentVoiceSessions: (userId, limit) =>
      db.voiceSession.findMany({ where: { userId }, orderBy: { startedAt: 'desc' }, take: limit, select: SESSION_SELECT }),
    async usageSince(userId, since) {
      const r = await db.voiceSession.aggregate({
        where: { userId, startedAt: { gte: since } },
        _count: { _all: true },
        _sum: { audioSeconds: true },
      })
      return { sessions: r._count._all, audioSeconds: r._sum.audioSeconds ?? 0 }
    },
    async recordTextUsage(userId, row) {
      await db.textUsage.create({ data: { userId, ...row } })
    },
  }
}

type MemoryUser = { id: string; email: string; name: string; image?: string | null; voiceKeywords?: string[]; languages?: string[] }

/**
 * For tests. `users` reads the user list Better Auth's memory adapter writes
 * to (its `kudkeysUser` table, which it replaces on every transaction), so a
 * user who signs up is found here too.
 */
export function memoryStore(users: () => MemoryUser[]): Store & {
  voiceSessions: (VoiceSessionRow & { userId: string })[]
  textUsage: (TextUsageRow & { userId: string })[]
} {
  const voiceSessions: (VoiceSessionRow & { userId: string })[] = []
  const textUsage: (TextUsageRow & { userId: string })[] = []
  const profile = (u: MemoryUser): Profile => ({
    id: u.id,
    email: u.email,
    name: u.name,
    image: u.image ?? null,
    voiceKeywords: u.voiceKeywords ?? [],
    languages: u.languages ?? DEFAULT_LANGUAGES,
  })
  const since = (userId: string, t: Date) => voiceSessions.filter((s) => s.userId === userId && s.startedAt >= t)
  return {
    voiceSessions,
    textUsage,
    async recordTextUsage(userId, row) {
      textUsage.push({ userId, ...row })
    },
    async getProfile(userId) {
      const u = users().find((x) => x.id === userId)
      return u ? profile(u) : null
    },
    async updateProfile(userId, patch) {
      const u = users().find((x) => x.id === userId)
      if (!u) return null
      Object.assign(u, patch)
      return profile(u)
    },
    async recordVoiceSession(userId, s) {
      voiceSessions.push({ id: crypto.randomUUID(), userId, ...s })
    },
    async recentVoiceSessions(userId, limit) {
      return voiceSessions
        .filter((s) => s.userId === userId)
        .sort((a, b) => b.startedAt.getTime() - a.startedAt.getTime())
        .slice(0, limit)
        .map(({ userId: _, ...row }) => row)
    },
    async usageSince(userId, t) {
      const rows = since(userId, t)
      return { sessions: rows.length, audioSeconds: rows.reduce((n, r) => n + r.audioSeconds, 0) }
    },
  }
}
