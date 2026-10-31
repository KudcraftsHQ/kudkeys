// The three text operations on top of Groq chat in JSON mode: tidy (dictation
// → clean prose, with questions about unsure words), fix (typos) and instruct
// (apply a spoken instruction). The model's JSON is checked with zod and
// retried once when it is invalid; question offsets are computed and checked
// here, never taken from the model.
//
// Size: max_completion_tokens follows the input, a long tidy is split into
// chunks at paragraph or sentence ends, and every request is counted against a
// per-model tokens-per-minute budget (Groq's free tier: 8k). A request that can
// never fit is "too_long" (413); one that fits later is 429 with Retry-After.

import { z } from 'zod'
import { TokenBudget } from './budget'
import { chatJson, InvalidModelOutput, TextModelError, type ChatConfig } from './groq-chat'
import { systemPrompt, type PromptContext, type TextKind } from './prompts'

export type Segment = { text: string; confidence?: number }
export type Question = { id: string; original: string; start: number; end: number; options: string[] }

export type TidyResult = { text: string; questions: Question[] }
export type FixResult = { text: string; changed: boolean }
export type InstructResult = { text: string }

export interface TextEngine {
  /** The Groq model each kind uses. */
  readonly models: Record<TextKind, string>
  tidy(input: { text: string; segments?: Segment[] }, ctx: PromptContext): Promise<TidyResult>
  fix(input: { text: string }, ctx: PromptContext): Promise<FixResult>
  instruct(input: { instruction: string; text: string }, ctx: PromptContext): Promise<InstructResult>
}

/** Below this, a dictated phrase is listed as "unsure" for the model. */
export const LOW_CONFIDENCE = 0.7
export const MAX_QUESTIONS = 5
const MAX_OPTIONS = 3
const MAX_OUTPUT_TOKENS = 8_192
/** Transcript tokens per tidy request; longer dictations are tidied in chunks of about this size. */
export const TIDY_CHUNK_TOKENS = 2_500
/** A Fix whose edit distance is more than this share of the input rewrote the text: refused. */
export const FIX_MAX_CHANGE = 0.4
/** ...but a few characters are always allowed, so "hi" → "Hi." on a short text is fine. */
const FIX_MIN_EDITS = 8

export const TIMEOUT_MS: Record<TextKind, number> = { fix: 15_000, tidy: 45_000, instruct: 45_000 }

const tidyOut = z.object({
  text: z.string(),
  questions: z
    .array(z.object({ word: z.string(), options: z.array(z.string()).default([]), start: z.number().int().optional() }).loose())
    .default([]),
})
const fixOut = z.object({ text: z.string(), changed: z.boolean().optional() })
const instructOut = z.object({ text: z.string() })

/** A rough token count that errs high: mixed Indonesian–English runs about 3.5–4 chars per token. */
export function estimateTokens(s: string): number {
  return Math.ceil(s.length / 3)
}

/** Room for the reply: about as long as the input, plus a margin for the JSON and a little reasoning. */
export function maxTokens(kind: TextKind, inputTokens: number): number {
  const n = Math.ceil(inputTokens * 1.25) + 768
  // Instruct may write more than it was given ("write a reply…" on an empty field).
  return Math.min(MAX_OUTPUT_TOKENS, kind === 'instruct' ? Math.max(n, 1536) : n)
}

export type EngineConfig = Omit<ChatConfig, 'model'> & {
  /** For fix and instruct, and for tidy when tidyModel is unset. */
  model: string
  tidyModel?: string
  /** Groq's tokens per minute per model (prompt + max_completion_tokens). Unset or 0 = not counted. */
  tokensPerMinute?: number
}

export function createTextEngine(cfg: EngineConfig, opts: { timeoutMs?: Partial<Record<TextKind, number>> } = {}): TextEngine {
  const timeouts = { ...TIMEOUT_MS, ...opts.timeoutMs }
  const models: Record<TextKind, string> = { tidy: cfg.tidyModel ?? cfg.model, fix: cfg.model, instruct: cfg.model }

  const budget = new TokenBudget(cfg.tokensPerMinute ?? 0)

  /** One request as sent: its messages and what it costs against the budget. */
  function plan(kind: TextKind, ctx: PromptContext, user: object, inputTokens: number) {
    const system = systemPrompt(kind, ctx)
    const userMsg = JSON.stringify(user)
    const max = maxTokens(kind, inputTokens)
    return { system, userMsg, max, cost: estimateTokens(system) + estimateTokens(userMsg) + max }
  }
  type Plan = ReturnType<typeof plan>

  /** Reserves [cost] tokens of [kind]'s model or throws too_long / rate limited. */
  function reserve(kind: TextKind, cost: number) {
    const b = budget.take(models[kind], cost)
    if (b.ok) return
    if (b.tooLong) throw new TextModelError(413, 'too_long')
    throw new TextModelError(429, 'budget', b.retryAfter)
  }

  /**
   * One planned call (already reserved), retried once when the reply isn't the JSON we asked for, if the budget
   * still has room for the retry. All within [deadline].
   */
  async function run<T>(kind: TextKind, p: Plan, deadline: number, schema: z.ZodType<T>, accept: (out: T) => boolean = () => true): Promise<T> {
    for (let attempt = 1; ; attempt++) {
      const remaining = deadline - Date.now()
      if (remaining <= 0) throw new TextModelError(503, 'timeout')
      try {
        const raw = await chatJson(
          { apiKey: cfg.apiKey, url: cfg.url, model: models[kind] },
          { system: p.system, user: p.userMsg, maxTokens: p.max, signal: AbortSignal.timeout(remaining) },
        )
        const parsed = schema.safeParse(raw)
        if (!parsed.success || !accept(parsed.data)) throw new InvalidModelOutput('schema')
        return parsed.data
      } catch (e) {
        if (!(e instanceof InvalidModelOutput)) throw e
        if (attempt >= 2 || !budget.take(models[kind], p.cost).ok) throw new TextModelError(503, 'invalid_model_output')
        console.warn(`[kudkeys] text ${kind}: invalid model output (${e.message}), retrying`)
      }
    }
  }

  async function single<T>(kind: TextKind, ctx: PromptContext, user: object, inputTokens: number, schema: z.ZodType<T>, accept?: (out: T) => boolean) {
    const deadline = Date.now() + timeouts[kind]
    const p = plan(kind, ctx, user, inputTokens)
    reserve(kind, p.cost)
    return run(kind, p, deadline, schema, accept)
  }

  return {
    models,

    async tidy({ text, segments }, ctx) {
      if (!text.trim()) return { text: '', questions: [] }
      const deadline = Date.now() + timeouts.tidy
      const unsure = (segments ?? [])
        .filter((s) => s.confidence !== undefined && s.confidence < LOW_CONFIDENCE && s.text.trim())
        .map((s) => s.text.trim())
      // Long dictations go in chunks, in parallel; the whole set must fit the budget or nothing is sent.
      const chunks = splitForTidy(text, TIDY_CHUNK_TOKENS * 3)
      const plans = chunks.map((c) => {
        const mine = unsure.filter((u) => c.includes(u))
        return plan('tidy', ctx, mine.length ? { transcript: c, unsure: mine } : { transcript: c }, estimateTokens(c))
      })
      reserve('tidy', plans.reduce((n, p) => n + p.cost, 0))
      const outs = await Promise.all(plans.map((p) => run('tidy', p, deadline, tidyOut, (o) => Boolean(o.text.trim()))))
      let joined = ''
      const questions: Question[] = []
      for (const out of outs) {
        // Offsets index the trimmed text, which is what the client gets.
        const t = out.text.trim()
        const sep = joinSeparator(joined, t)
        const offset = joined.length + sep.length
        for (const q of placeQuestions(t, out.questions)) questions.push({ ...q, start: q.start + offset, end: q.end + offset })
        joined += sep + t
      }
      return { text: joined, questions: questions.slice(0, MAX_QUESTIONS).map((q, i) => ({ ...q, id: `q${i + 1}` })) }
    },

    async fix({ text }, ctx) {
      if (!text.trim()) return { text, changed: false }
      const out = await single('fix', ctx, { text }, estimateTokens(text), fixOut, (o) => Boolean(o.text.trim()))
      // Decided by comparing, not by the model's flag.
      if (out.text === text) return { text, changed: false }
      // A "fix" that rewrites the text (a pasted "ignore previous instructions…", a model that answered instead)
      // is refused: the field keeps what was written.
      if (editDistance(text, out.text) > Math.max(FIX_MIN_EDITS, text.length * FIX_MAX_CHANGE)) {
        throw new TextModelError(422, 'changed_too_much')
      }
      return { text: out.text, changed: true }
    },

    async instruct({ instruction, text }, ctx) {
      const out = await single('instruct', ctx, { instruction, text }, estimateTokens(text) + estimateTokens(instruction), instructOut, (o) =>
        Boolean(o.text.trim()),
      )
      return { text: out.text }
    },
  }
}

/** Paragraph break when either side has several lines (a list, paragraphs), else a space. Same rule as the app. */
export function joinSeparator(before: string, after: string): string {
  if (!before.trim() || !after.trim()) return ''
  return before.trim().includes('\n') || after.trim().includes('\n') ? '\n\n' : ' '
}

/**
 * Splits [text] into pieces of at most [maxChars], in order, cutting at paragraph breaks, then sentence ends, then
 * spaces (a piece is only cut mid-word when it has no space at all). Pieces are trimmed and never empty.
 */
export function splitForTidy(text: string, maxChars: number): string[] {
  const t = text.trim()
  if (t.length <= maxChars) return [t]
  const out: string[] = []
  let rest = t
  while (rest.length > maxChars) {
    const window = rest.slice(0, maxChars + 1)
    const cut = lastCut(window, /\n\s*\n/g) ?? lastCut(window, /\n/g) ?? lastCut(window, /[.!?…]["')\]]?\s+/g) ?? lastCut(window, /\s+/g) ?? maxChars
    out.push(rest.slice(0, cut).trim())
    rest = rest.slice(cut).trimStart()
  }
  if (rest.trim()) out.push(rest.trim())
  return out.filter(Boolean)
}

/** The end of the last match of [re] in [s] that leaves at least a third of [s] before it, or null. */
function lastCut(s: string, re: RegExp): number | null {
  let best: number | null = null
  for (const m of s.matchAll(re)) {
    const end = m.index! + m[0].length
    if (m.index! >= s.length / 3 && end < s.length) best = end
  }
  return best
}

/** Levenshtein distance in UTF-16 units; two rows, so 4,000 × 4,000 chars is quick. */
export function editDistance(a: string, b: string): number {
  if (a === b) return 0
  if (!a.length) return b.length
  if (!b.length) return a.length
  let prev = new Int32Array(b.length + 1)
  let cur = new Int32Array(b.length + 1)
  for (let j = 0; j <= b.length; j++) prev[j] = j
  for (let i = 1; i <= a.length; i++) {
    cur[0] = i
    const ca = a.charCodeAt(i - 1)
    for (let j = 1; j <= b.length; j++) {
      const sub = prev[j - 1]! + (ca === b.charCodeAt(j - 1) ? 0 : 1)
      cur[j] = Math.min(sub, prev[j]! + 1, cur[j - 1]! + 1)
    }
    ;[prev, cur] = [cur, prev]
  }
  return prev[b.length]!
}

const WORD_CHAR = /[\p{L}\p{N}_]/u

/** Letters and digits only, lower-cased: "Feno," and "feno" are the same word. */
const bare = (s: string) => s.replace(/[^\p{L}\p{N}]/gu, '').toLowerCase()

function isWholeWord(text: string, start: number, end: number): boolean {
  return !WORD_CHAR.test(text[start - 1] ?? '') && !WORD_CHAR.test(text[end] ?? '')
}

/**
 * Turns the model's questions into ones whose `start`/`end` point at
 * `original` in `text` (text.slice(start, end) === original, on word
 * boundaries). A question whose word isn't in the text, or has no usable
 * option, is dropped. Two questions never cover the same characters.
 */
export function placeQuestions(text: string, raw: Array<{ word: string; options: string[]; start?: number }>): Question[] {
  const taken: Array<[number, number]> = []
  const overlaps = (s: number, e: number) => taken.some(([a, b]) => s < b && a < e)
  const out: Question[] = []
  for (const q of raw) {
    if (out.length >= MAX_QUESTIONS) break
    const word = q.word.trim()
    if (!word || word.length > 60) continue
    const seen = new Set<string>()
    const options = q.options
      .map((o) => o.trim())
      // An option that differs only in case, punctuation or spacing is no alternative.
      .filter((o) => o && bare(o) && bare(o) !== bare(word) && o.length <= 60 && !seen.has(bare(o)) && seen.add(bare(o)))
      .slice(0, MAX_OPTIONS)
    if (!options.length) continue

    const fits = (s: number) => s >= 0 && text.startsWith(word, s) && isWholeWord(text, s, s + word.length) && !overlaps(s, s + word.length)
    let start = q.start !== undefined && fits(q.start) ? q.start : -1
    if (start < 0) {
      // The model's offset (if any) is only a hint: take the whole-word occurrence nearest to it.
      const hint = q.start ?? 0
      for (let i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
        if (fits(i) && (start < 0 || Math.abs(i - hint) < Math.abs(start - hint))) start = i
      }
    }
    if (start < 0) continue
    const end = start + word.length
    taken.push([start, end])
    out.push({ id: `q${out.length + 1}`, original: word, start, end, options })
  }
  return out.sort((a, b) => a.start - b.start).map((q, i) => ({ ...q, id: `q${i + 1}` }))
}
