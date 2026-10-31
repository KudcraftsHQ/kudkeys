// /api/text/tidy, /fix and /instruct: the keyboard's AI text actions, on
// Groq chat (server/text/). Session tokens only (requireUser in app.ts; a
// legacy static token is no session, so it gets 401). Contract in
// server/README.md and docs/v0.3-spec.md.
//
// Privacy: request and response text are never logged or stored. Each call
// that reaches the model writes one TextUsage row with counts and timing; for
// `private: true` the counts are null as well.

import { Hono } from 'hono'
import type { Context } from 'hono'
import { z } from 'zod'
import type { AppEnv } from '../app'
import { RateLimiter } from '../lib/ratelimit'
import type { Store } from '../lib/store'
import type { TextEngine } from '../text/engine'
import { TextModelError } from '../text/groq-chat'
import type { PromptContext, TextKind } from '../text/prompts'

export const MAX_TIDY_CHARS = 20_000
export const MAX_FIX_CHARS = 4_000
export const MAX_INSTRUCTION_CHARS = 2_000
const MAX_SEGMENTS = 2_000

const languages = z
  .array(z.string().regex(/^[a-z]{2,3}$/, 'languages are ISO 639-1 codes like "id" or "en"'))
  .min(1)
  .max(10)
  .optional()

export const tidyBody = z
  .object({
    text: z.string().max(MAX_TIDY_CHARS),
    segments: z
      .array(z.object({ text: z.string().max(MAX_TIDY_CHARS), confidence: z.number().min(0).max(1).optional() }).strict())
      .max(MAX_SEGMENTS)
      .optional(),
    languages,
    private: z.boolean().optional(),
  })
  .strict()

export const fixBody = z.object({ text: z.string().max(MAX_FIX_CHARS), languages, private: z.boolean().optional() }).strict()

export const instructBody = z
  .object({
    instruction: z.string().trim().min(1, 'instruction is empty').max(MAX_INSTRUCTION_CHARS),
    text: z.string().max(MAX_TIDY_CHARS),
    languages,
    private: z.boolean().optional(),
  })
  .strict()

export type TextRouterDeps = {
  store: Store
  /** null when GROQ_API_KEY is missing: every call answers 503. */
  engine: TextEngine | null
  /** Calls per user per minute, all three kinds together. */
  perMinute?: number
  now?: () => number
}

export function textRouter(deps: TextRouterDeps) {
  const r = new Hono<AppEnv>()
  const limiter = new RateLimiter(deps.perMinute ?? 30, 60_000, deps.now)

  async function handle<B extends { languages?: string[]; private?: boolean }, R extends { text: string }>(
    c: Context<AppEnv>,
    kind: TextKind,
    schema: z.ZodType<B>,
    charsIn: (b: B) => number,
    call: (engine: TextEngine, body: B, ctx: PromptContext) => Promise<R>,
  ) {
    const parsed = schema.safeParse(await c.req.json().catch(() => undefined))
    if (!parsed.success) {
      return c.json(
        { error: 'invalid', issues: parsed.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message })) },
        400,
      )
    }
    const body = parsed.data
    const user = c.get('user')
    if (!deps.engine) return c.json({ error: 'unavailable', reason: 'text_not_configured' }, 503)

    const wait = limiter.take(user.id)
    if (wait !== null) {
      c.header('Retry-After', String(wait))
      return c.json({ error: 'rate_limited', retryAfter: wait }, 429)
    }

    let profile
    try {
      profile = await deps.store.getProfile(user.id)
    } catch (e) {
      console.error(`[kudkeys] text ${kind}: profile lookup failed: ${(e as Error).message}`)
      return c.json({ error: 'unavailable', reason: 'profile_lookup_failed' }, 503)
    }
    const ctx: PromptContext = {
      keywords: profile?.voiceKeywords ?? [],
      languages: body.languages ?? profile?.languages ?? ['id', 'en'],
    }

    const engine = deps.engine
    const t0 = performance.now()
    let result: R | null = null
    let failure: TextModelError | null = null
    try {
      result = await call(engine, body, ctx)
    } catch (e) {
      failure = e instanceof TextModelError ? e : new TextModelError(503, 'internal')
      if (!(e instanceof TextModelError)) console.error(`[kudkeys] text ${kind}: ${(e as Error).name}`)
    }
    const ms = Math.round(performance.now() - t0)
    const priv = body.private === true
    deps.store
      .recordTextUsage(user.id, {
        kind,
        charsIn: priv ? null : charsIn(body),
        charsOut: priv || !result ? null : result.text.length,
        ms,
        model: engine.models[kind],
        ok: result !== null,
      })
      .catch((e) => console.error(`[kudkeys] text ${kind}: usage row not written: ${(e as Error).message}`))

    if (failure) {
      // Status and reason only: never the text.
      console.warn(`[kudkeys] text ${kind}: ${failure.status} ${failure.reason} after ${ms} ms`)
      if (failure.status === 429) {
        const after = failure.retryAfter ?? 10
        c.header('Retry-After', String(after))
        return c.json({ error: 'rate_limited', reason: failure.reason, retryAfter: after }, 429)
      }
      return c.json({ error: 'unavailable', reason: failure.reason }, 503)
    }
    return c.json(result!)
  }

  r.post('/tidy', (c) =>
    handle(c, 'tidy', tidyBody, (b) => b.text.length, (e, b, ctx) => e.tidy({ text: b.text, segments: b.segments }, ctx)),
  )
  r.post('/fix', (c) => handle(c, 'fix', fixBody, (b) => b.text.length, (e, b, ctx) => e.fix({ text: b.text }, ctx)))
  r.post('/instruct', (c) =>
    handle(
      c,
      'instruct',
      instructBody,
      (b) => b.text.length + b.instruction.length,
      (e, b, ctx) => e.instruct({ instruction: b.instruction, text: b.text }, ctx),
    ),
  )
  return r
}
