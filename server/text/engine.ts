// The three text operations on top of Groq chat in JSON mode: tidy (dictation
// → clean prose, with questions about unsure words), fix (typos) and instruct
// (apply a spoken instruction). The model's JSON is checked with zod and
// retried once when it is invalid; question offsets are computed and checked
// here, never taken from the model.

import { z } from 'zod'
import { chatJson, InvalidModelOutput, TextModelError, type ChatConfig } from './groq-chat'
import { systemPrompt, type PromptContext, type TextKind } from './prompts'

export type Segment = { text: string; confidence?: number }
export type Question = { id: string; original: string; start: number; end: number; options: string[] }

export type TidyResult = { text: string; questions: Question[] }
export type FixResult = { text: string; changed: boolean }
export type InstructResult = { text: string }

export interface TextEngine {
  readonly model: string
  tidy(input: { text: string; segments?: Segment[] }, ctx: PromptContext): Promise<TidyResult>
  fix(input: { text: string }, ctx: PromptContext): Promise<FixResult>
  instruct(input: { instruction: string; text: string }, ctx: PromptContext): Promise<InstructResult>
}

/** Below this, a dictated phrase is listed as "unsure" for the model. */
export const LOW_CONFIDENCE = 0.7
export const MAX_QUESTIONS = 5
const MAX_OPTIONS = 3
const MAX_OUTPUT_TOKENS = 16_000

export const TIMEOUT_MS: Record<TextKind, number> = { fix: 15_000, tidy: 45_000, instruct: 45_000 }

const tidyOut = z.object({
  text: z.string(),
  questions: z
    .array(z.object({ word: z.string(), options: z.array(z.string()).default([]), start: z.number().int().optional() }).loose())
    .default([]),
})
const fixOut = z.object({ text: z.string(), changed: z.boolean().optional() })
const instructOut = z.object({ text: z.string() })

/** Room for the reply (about as long as the input) plus a little reasoning. */
function maxTokens(chars: number): number {
  return Math.min(MAX_OUTPUT_TOKENS, 1536 + Math.ceil(chars / 2))
}

export function createTextEngine(cfg: ChatConfig, opts: { timeoutMs?: Partial<Record<TextKind, number>> } = {}): TextEngine {
  const timeouts = { ...TIMEOUT_MS, ...opts.timeoutMs }

  /** One call, retried once when the reply isn't the JSON we asked for. All within the kind's deadline. */
  async function run<T>(
    kind: TextKind,
    ctx: PromptContext,
    user: object,
    chars: number,
    schema: z.ZodType<T>,
    accept: (out: T) => boolean = () => true,
  ): Promise<T> {
    const deadline = Date.now() + timeouts[kind]
    const system = systemPrompt(kind, ctx)
    const userMsg = JSON.stringify(user)
    for (let attempt = 1; ; attempt++) {
      const remaining = deadline - Date.now()
      if (remaining <= 0) throw new TextModelError(503, 'timeout')
      try {
        const raw = await chatJson(cfg, { system, user: userMsg, maxTokens: maxTokens(chars), signal: AbortSignal.timeout(remaining) })
        const parsed = schema.safeParse(raw)
        if (!parsed.success || !accept(parsed.data)) throw new InvalidModelOutput('schema')
        return parsed.data
      } catch (e) {
        if (!(e instanceof InvalidModelOutput)) throw e
        if (attempt >= 2) throw new TextModelError(503, 'invalid_model_output')
        console.warn(`[kudkeys] text ${kind}: invalid model output (${e.message}), retrying`)
      }
    }
  }

  return {
    model: cfg.model,

    async tidy({ text, segments }, ctx) {
      if (!text.trim()) return { text: '', questions: [] }
      const unsure = (segments ?? [])
        .filter((s) => s.confidence !== undefined && s.confidence < LOW_CONFIDENCE && s.text.trim())
        .map((s) => s.text.trim())
      const out = await run('tidy', ctx, unsure.length ? { transcript: text, unsure } : { transcript: text }, text.length, tidyOut, (o) =>
        Boolean(o.text.trim()),
      )
      return { text: out.text, questions: placeQuestions(out.text, out.questions) }
    },

    async fix({ text }, ctx) {
      if (!text.trim()) return { text, changed: false }
      const out = await run('fix', ctx, { text }, text.length, fixOut, (o) => Boolean(o.text.trim()))
      // Decided by comparing, not by the model's flag.
      return out.text === text ? { text, changed: false } : { text: out.text, changed: true }
    },

    async instruct({ instruction, text }, ctx) {
      const out = await run('instruct', ctx, { instruction, text }, text.length + instruction.length, instructOut, (o) =>
        Boolean(o.text.trim()),
      )
      return { text: out.text }
    },
  }
}

const WORD_CHAR = /[\p{L}\p{N}_]/u

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
      .filter((o) => o && o !== word && o.length <= 60 && !seen.has(o) && seen.add(o))
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
