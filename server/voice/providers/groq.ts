// Groq Whisper (OpenAI-compatible file transcription).
// Docs: https://console.groq.com/docs/speech-to-text · https://console.groq.com/docs/rate-limits
//
// Groq has no streaming endpoint, so the server cuts the mic stream into phrases at pauses
// (see segmenter.ts) and sends each phrase as a small WAV. Words appear per phrase, about as
// fast as Groq answers, not word by word.
//
// Limits shape the design: the base plan allows 20 requests/min and 7,200 audio-seconds/hour,
// and every request is billed as at least 10 s. So in-progress partials (re-sending the phrase
// so far) are off by default and, when on, only spend spare request budget.

import { DEFAULT_SEGMENTER, Segmenter, wavFile } from "../segmenter";
import type { ProviderEvents, ProviderOptions, StreamingTranscriber } from "./types";

export type GroqConfig = {
  apiKey: string;
  model: string;
  url: string;
  /** Milliseconds between in-progress partials; 0 turns them off. */
  partialMs: number;
  pauseMs: number;
  /** Requests per minute the account allows; partials keep a margin below it for finals. */
  rpm: number;
};

export const GROQ_TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
const SAMPLE_RATE = 16000;
const REQUEST_TIMEOUT_MS = 15_000;
const FINALS_MARGIN = 6; // requests per minute kept free for finals
const MAX_PROMPT_CHARS = 600; // Whisper's prompt is capped at 224 tokens

type VerboseJson = {
  text?: string;
  segments?: Array<{ text: string; avg_logprob?: number; no_speech_prob?: number }>;
};

class FatalError extends Error {}

export class GroqTranscriber implements StreamingTranscriber {
  readonly sampleRate = SAMPLE_RATE;
  readonly model: string;

  private readonly segmenter: Segmenter;
  private readonly language: string | undefined;
  private readonly prompt: string | undefined;
  private nextSegment = 0;
  private current: { n: number | null } = { n: null }; // the phrase in progress
  private pending = new Set<Promise<void>>();
  private requestTimes: number[] = [];
  private partialTimer: ReturnType<typeof setInterval> | null = null;
  private partialInFlight = false;
  private lastPartialVersion = -1;
  private finalized = new Set<number>();
  private partialSent = new Set<number>();
  private closed = false;

  constructor(
    private readonly cfg: GroqConfig,
    opts: ProviderOptions,
    private readonly events: ProviderEvents,
  ) {
    this.model = cfg.model;
    // Whisper takes one language. With several (id + en), let it detect per phrase.
    this.language = opts.languages.length === 1 ? opts.languages[0] : undefined;
    this.prompt = buildPrompt(opts);
    this.segmenter = new Segmenter({ ...DEFAULT_SEGMENTER, sampleRate: SAMPLE_RATE, pauseMs: cfg.pauseMs }, (samples) =>
      this.onPhrase(samples),
    );
    if (cfg.partialMs > 0) this.partialTimer = setInterval(() => this.maybePartial(), cfg.partialMs);
  }

  ready(): Promise<void> {
    return Promise.resolve();
  }

  appendAudio(samples: Int16Array): void {
    if (this.closed) return;
    this.segmenter.push(samples);
  }

  commit(): void {
    this.segmenter.flush();
  }

  async finish(timeoutMs: number): Promise<void> {
    this.segmenter.flush();
    if (this.partialTimer) clearInterval(this.partialTimer);
    const all = Promise.all([...this.pending]);
    await Promise.race([all, Bun.sleep(timeoutMs)]);
  }

  close(): void {
    this.closed = true;
    if (this.partialTimer) clearInterval(this.partialTimer);
  }

  private numberFor(slot: { n: number | null }): number {
    if (slot.n === null) slot.n = this.nextSegment++;
    return slot.n;
  }

  private onPhrase(samples: Int16Array) {
    const n = this.numberFor(this.current);
    this.current = { n: null };
    this.lastPartialVersion = -1;
    const job = this.transcribe(samples, true)
      .then((r) => {
        this.finalized.add(n);
        // An empty final is only worth sending when it has to clear a partial on screen.
        if (r && (r.text || this.partialSent.has(n))) this.events.onFinal(n, r.text, r.confidence);
      })
      .catch((e: Error) => {
        this.finalized.add(n);
        if (!this.closed) this.events.onError(e.message);
      })
      .finally(() => this.pending.delete(job));
    this.pending.add(job);
  }

  private maybePartial() {
    if (this.closed || this.partialInFlight || !this.segmenter.inPhrase) return;
    const cur = this.segmenter.current();
    if (!cur || cur.speechMs < 1000 || cur.version === this.lastPartialVersion) return;
    if (this.recentRequests() >= this.cfg.rpm - FINALS_MARGIN) return;
    const slot = this.current;
    const n = this.numberFor(slot);
    this.lastPartialVersion = cur.version;
    this.partialInFlight = true;
    this.transcribe(cur.samples, false)
      .then((r) => {
        // A partial that lands after its phrase was finalized would overwrite the final.
        if (r && r.text && !this.finalized.has(n) && !this.closed) {
          this.partialSent.add(n);
          this.events.onPartial(n, r.text);
        }
      })
      .catch((e) => {
        if (e instanceof FatalError && !this.closed) this.events.onError(e.message);
      })
      .finally(() => (this.partialInFlight = false));
  }

  private recentRequests(): number {
    const cutoff = Date.now() - 60_000;
    this.requestTimes = this.requestTimes.filter((t) => t > cutoff);
    return this.requestTimes.length;
  }

  /** One Whisper call. Finals retry once on 429/5xx; partials never retry. */
  private async transcribe(samples: Int16Array, isFinal: boolean): Promise<{ text: string; confidence?: number } | null> {
    for (let attempt = 0; ; attempt++) {
      this.requestTimes.push(Date.now());
      const form = new FormData();
      form.append("file", new Blob([wavFile(samples, SAMPLE_RATE)], { type: "audio/wav" }), "phrase.wav");
      form.append("model", this.cfg.model);
      form.append("response_format", "verbose_json");
      form.append("temperature", "0");
      if (this.language) form.append("language", this.language);
      if (this.prompt) form.append("prompt", this.prompt);

      let res: Response;
      try {
        res = await fetch(this.cfg.url, {
          method: "POST",
          headers: { Authorization: `Bearer ${this.cfg.apiKey}` },
          body: form,
          signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
        });
      } catch (e) {
        if (isFinal && attempt === 0) continue;
        throw new Error(`could not reach Groq: ${(e as Error).message}`);
      }

      if (res.ok) return parseResult((await res.json()) as VerboseJson);

      const detail = await errorMessage(res);
      if (res.status === 401 || res.status === 403) throw new FatalError(`Groq refused the key (${res.status}): ${detail}`);
      if (res.status === 400 || res.status === 404) throw new FatalError(`Groq rejected the request (${res.status}): ${detail}`);
      const retryable = res.status === 429 || res.status >= 500;
      if (isFinal && retryable && attempt === 0) {
        const after = Number(res.headers.get("retry-after"));
        await Bun.sleep(Math.min(Number.isFinite(after) && after > 0 ? after * 1000 : 500, 5000));
        continue;
      }
      if (!isFinal) return null;
      throw new Error(`Groq error ${res.status}: ${detail}`);
    }
  }
}

/** Drops Whisper's made-up text on near-silence and turns avg_logprob into a 0–1 confidence. */
export function parseResult(body: VerboseJson): { text: string; confidence?: number } {
  if (!body.segments) return { text: (body.text ?? "").trim() };
  const kept = body.segments.filter((s) => {
    const noSpeech = s.no_speech_prob ?? 0;
    const logprob = s.avg_logprob ?? 0;
    return !(noSpeech > 0.9 || (noSpeech > 0.6 && logprob < -1));
  });
  const text = kept
    .map((s) => s.text.trim())
    .filter(Boolean)
    .join(" ");
  const logprobs = kept.map((s) => s.avg_logprob).filter((x): x is number => typeof x === "number");
  const confidence = logprobs.length ? Math.round(Math.exp(Math.min(...logprobs)) * 100) / 100 : undefined;
  return { text, confidence };
}

function buildPrompt(opts: ProviderOptions): string | undefined {
  const parts: string[] = [];
  if (opts.prompt) parts.push(opts.prompt.trim());
  if (opts.keywords?.length) parts.push(opts.keywords.join(", "));
  const p = parts.join(" ").trim();
  return p ? p.slice(0, MAX_PROMPT_CHARS) : undefined;
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: { message?: string } };
    return body.error?.message ?? res.statusText;
  } catch {
    return res.statusText;
  }
}
