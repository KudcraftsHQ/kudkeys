// Groq chat completions (OpenAI-compatible) in JSON mode, for the text
// endpoints. Docs: https://console.groq.com/docs/text-chat
//
// Never logs the messages or the reply: only status codes and timings.

export const GROQ_CHAT_URL = 'https://api.groq.com/openai/v1/chat/completions'

export type ChatConfig = { apiKey: string; url: string; model: string }

export type ChatRequest = {
  system: string
  user: string
  maxTokens: number
  /** Deadline for this one request. */
  signal: AbortSignal
}

/** What the endpoint answers when the model cannot be used. */
export class TextModelError extends Error {
  constructor(
    readonly status: 429 | 503,
    readonly reason: string,
    readonly retryAfter?: number,
  ) {
    super(reason)
  }
}

/** The model answered, but not with valid JSON. Worth one retry. */
export class InvalidModelOutput extends Error {}

/** Extra request fields some models need: no long reasoning for a keyboard. */
export function modelExtras(model: string): Record<string, unknown> {
  if (model.startsWith('openai/gpt-oss')) return { reasoning_effort: 'low', include_reasoning: false }
  if (model.startsWith('qwen/')) return { reasoning_effort: 'none' }
  return {}
}

function retryAfterSeconds(res: Response): number {
  const h = res.headers.get('retry-after')
  const n = h ? Number.parseFloat(h) : Number.NaN
  return Number.isFinite(n) && n > 0 ? Math.min(Math.ceil(n), 120) : 10
}

/** One chat completion; returns the parsed JSON object the model wrote. */
export async function chatJson(cfg: ChatConfig, req: ChatRequest): Promise<unknown> {
  let res: Response
  try {
    res = await fetch(cfg.url, {
      method: 'POST',
      headers: { authorization: `Bearer ${cfg.apiKey}`, 'content-type': 'application/json' },
      body: JSON.stringify({
        model: cfg.model,
        messages: [
          { role: 'system', content: req.system },
          { role: 'user', content: req.user },
        ],
        response_format: { type: 'json_object' },
        temperature: 0.2,
        max_completion_tokens: req.maxTokens,
        ...modelExtras(cfg.model),
      }),
      signal: req.signal,
    })
  } catch (e) {
    const name = (e as Error).name
    throw new TextModelError(503, name === 'TimeoutError' || name === 'AbortError' ? 'timeout' : 'network')
  }

  if (res.status === 429) {
    await res.body?.cancel()
    throw new TextModelError(429, 'model_rate_limited', retryAfterSeconds(res))
  }
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as { error?: { code?: string } } | null
    // JSON mode: Groq refuses a reply that isn't valid JSON with 400 json_validate_failed.
    if (res.status === 400 && body?.error?.code === 'json_validate_failed') throw new InvalidModelOutput('json_validate_failed')
    throw new TextModelError(503, `model_http_${res.status}`)
  }

  let data: { choices?: Array<{ message?: { content?: string | null }; finish_reason?: string }> }
  try {
    data = (await res.json()) as typeof data
  } catch (e) {
    const name = (e as Error).name
    if (name === 'TimeoutError' || name === 'AbortError') throw new TextModelError(503, 'timeout')
    throw new InvalidModelOutput('response_not_json')
  }
  const choice = data.choices?.[0]
  if (choice?.finish_reason === 'length') throw new InvalidModelOutput('truncated')
  const content = choice?.message?.content
  if (typeof content !== 'string') throw new InvalidModelOutput('no_content')
  try {
    return JSON.parse(content)
  } catch {
    throw new InvalidModelOutput('content_not_json')
  }
}
