// The signed-in user's profile and usage. The Android app and the web app
// both use these; the contract is documented in server/README.md.

import { Hono } from 'hono'
import { z } from 'zod'
import type { AppEnv } from '../app'
import type { Store } from '../lib/store'
import { minutes, periodStarts } from '../lib/usage'

export const MAX_KEYWORDS = 100
export const MAX_KEYWORD_LENGTH = 100
const RECENT_SESSIONS = 50

const keyword = z
  .string()
  .trim()
  .min(1, 'keywords cannot be empty')
  .max(MAX_KEYWORD_LENGTH, `keywords are at most ${MAX_KEYWORD_LENGTH} characters`)
  // Whisper's prompt is one line; the OpenAI provider refuses angle brackets.
  .refine((k) => !/[<>\r\n]/.test(k), 'keywords cannot contain < > or line breaks')

export const profilePatch = z
  .object({
    name: z.string().trim().min(1).max(100).optional(),
    image: z.url().max(2000).nullable().optional(),
    voiceKeywords: z.array(keyword).max(MAX_KEYWORDS, `at most ${MAX_KEYWORDS} keywords`).optional(),
    languages: z
      .array(z.string().regex(/^[a-z]{2,3}$/, 'languages are ISO 639-1 codes like "id" or "en"'))
      .min(1, 'at least one language')
      .max(10)
      .optional(),
  })
  .strict()

/** Same word in a different case is one keyword; the first spelling wins. */
function dedupe(list: string[]): string[] {
  const seen = new Set<string>()
  return list.filter((k) => {
    const key = k.toLowerCase()
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

export function meRouter(deps: { store: Store; now?: () => number }) {
  const r = new Hono<AppEnv>()

  r.get('/', async (c) => {
    const p = await deps.store.getProfile(c.get('user').id)
    if (!p) return c.json({ error: 'not_found' }, 404)
    return c.json(p)
  })

  r.patch('/', async (c) => {
    const body = await c.req.json().catch(() => undefined)
    const parsed = profilePatch.safeParse(body)
    if (!parsed.success) {
      return c.json(
        { error: 'invalid', issues: parsed.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })) },
        400,
      )
    }
    const patch = parsed.data
    if (patch.voiceKeywords) patch.voiceKeywords = dedupe(patch.voiceKeywords)
    if (patch.languages) patch.languages = dedupe(patch.languages)
    const p = await deps.store.updateProfile(c.get('user').id, patch)
    if (!p) return c.json({ error: 'not_found' }, 404)
    return c.json(p)
  })

  r.get('/usage', async (c) => {
    const userId = c.get('user').id
    const now = new Date(deps.now?.() ?? Date.now())
    const { week, month } = periodStarts(now)
    const [w, m, recent] = await Promise.all([
      deps.store.usageSince(userId, week),
      deps.store.usageSince(userId, month),
      deps.store.recentVoiceSessions(userId, RECENT_SESSIONS),
    ])
    return c.json({
      week: { since: week.toISOString(), sessions: w.sessions, audioMinutes: minutes(w.audioSeconds) },
      month: { since: month.toISOString(), sessions: m.sessions, audioMinutes: minutes(m.audioSeconds) },
      sessions: recent.map((s) => ({
        id: s.id,
        session: s.session,
        startedAt: s.startedAt.toISOString(),
        endedAt: s.endedAt.toISOString(),
        audioSeconds: s.audioSeconds,
        finals: s.finals,
        outcome: s.outcome,
        model: s.model,
      })),
    })
  })

  return r
}
