// Groq's free tier allows a fixed number of tokens per minute per model, and
// counts the tokens a request *asks* for (prompt + max_completion_tokens), not
// the ones it uses. This keeps our own count, so a request that cannot fit is
// refused before it reaches Groq: "too long" when it can never fit, 429 with
// an honest Retry-After when it fits once earlier requests age out.

const WINDOW_MS = 60_000

export type BudgetAnswer = { ok: true } | { ok: false; tooLong: true } | { ok: false; tooLong: false; retryAfter: number }

export class TokenBudget {
  private readonly spent = new Map<string, Array<{ at: number; tokens: number }>>()

  constructor(
    /** Tokens per minute per model; 0 or less = no limit. */
    readonly perMinute: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Takes [tokens] from [model]'s minute, or says why not. */
  take(model: string, tokens: number): BudgetAnswer {
    if (this.perMinute <= 0) return { ok: true }
    if (tokens > this.perMinute) return { ok: false, tooLong: true }
    const t = this.now()
    const list = (this.spent.get(model) ?? []).filter((e) => e.at > t - WINDOW_MS)
    this.spent.set(model, list)
    let used = list.reduce((s, e) => s + e.tokens, 0)
    if (used + tokens <= this.perMinute) {
      list.push({ at: t, tokens })
      return { ok: true }
    }
    // When enough of the oldest entries have aged out.
    for (const e of list) {
      used -= e.tokens
      if (used + tokens <= this.perMinute) return { ok: false, tooLong: false, retryAfter: Math.max(1, Math.ceil((e.at + WINDOW_MS - t) / 1000)) }
    }
    return { ok: false, tooLong: false, retryAfter: 60 }
  }
}
