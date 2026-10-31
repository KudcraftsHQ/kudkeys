// OpenAI realtime transcription (GA API, transcription-only session).
// Docs: https://developers.openai.com/api/docs/guides/realtime-transcription
//
// - Connect to wss://api.openai.com/v1/realtime?intent=transcription (no model in the URL).
// - session.update with session.type "transcription"; audio must be 24 kHz PCM16.
// - gpt-live-transcribe streams text as audio arrives, takes `languages` (plural) and
//   `keywords`, and has no server VAD, so turn_detection is null and we commit ourselves.
// - It returns no confidence scores. Asking about unsure words needs another signal later.

import { int16ToBase64 } from "../audio";
import type { ProviderEvents, ProviderOptions, StreamingTranscriber } from "./types";

export type OpenAIConfig = {
  apiKey: string;
  model: string;
  url: string;
  /** Optional; only sent when set, since support differs between models. */
  delay?: string;
  noiseReduction?: "near_field" | "far_field";
};

export const OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime?intent=transcription";
const SAMPLE_RATE = 24000;
const READY_FALLBACK_MS = 3000;
const CONNECT_TIMEOUT_MS = 10000;

type UpstreamEvent = {
  type: string;
  item_id?: string;
  delta?: string;
  transcript?: string;
  error?: { message?: string; code?: string };
};

export class OpenAITranscriber implements StreamingTranscriber {
  readonly sampleRate = SAMPLE_RATE;
  readonly model: string;

  private ws: WebSocket;
  private readyPromise: Promise<void>;
  private isReady = false;
  private queue: string[] = [];
  private closed = false;

  private appendedSinceCommit = 0;
  private commitsAwaiting = 0;
  private segments = new Map<string, number>(); // item_id → segment number
  private completed = new Set<string>();
  private settleWaiters: Array<() => void> = [];

  constructor(
    private readonly cfg: OpenAIConfig,
    private readonly opts: ProviderOptions,
    private readonly events: ProviderEvents,
  ) {
    this.model = cfg.model;
    this.ws = new WebSocket(cfg.url, {
      headers: { Authorization: `Bearer ${cfg.apiKey}` },
    } as unknown as string[]);

    this.readyPromise = new Promise<void>((resolve, reject) => {
      const connectTimer = setTimeout(() => reject(new Error("timed out connecting to OpenAI")), CONNECT_TIMEOUT_MS);
      let fallback: ReturnType<typeof setTimeout> | undefined;
      const markReady = () => {
        if (this.isReady) return;
        clearTimeout(connectTimer);
        clearTimeout(fallback);
        this.isReady = true;
        for (const m of this.queue) this.ws.send(m);
        this.queue = [];
        resolve();
      };

      this.ws.addEventListener("open", () => {
        this.ws.send(JSON.stringify(this.sessionUpdate()));
        // Wait for session.updated, but don't hang if the server stays quiet.
        fallback = setTimeout(markReady, READY_FALLBACK_MS);
      });
      this.ws.addEventListener("message", (ev) => {
        const e = parse(ev.data);
        if (!e) return;
        if (e.type === "session.updated") markReady();
        if (e.type === "error" && !this.isReady) {
          clearTimeout(connectTimer);
          clearTimeout(fallback);
          reject(new Error(e.error?.message ?? "OpenAI refused the session"));
          return;
        }
        this.handle(e);
      });
      this.ws.addEventListener("close", (ev) => {
        clearTimeout(connectTimer);
        clearTimeout(fallback);
        if (!this.isReady) reject(new Error(`OpenAI closed the connection (${ev.code})`));
        else if (!this.closed) this.events.onError(`OpenAI closed the connection (${ev.code})`);
        this.closed = true;
        this.wake();
      });
      this.ws.addEventListener("error", () => {
        // A close event always follows; it carries the useful part.
      });
    });
    // Avoid an unhandled rejection when nobody awaits ready() before it fails.
    this.readyPromise.catch(() => {});
  }

  ready(): Promise<void> {
    return this.readyPromise;
  }

  appendAudio(samples: Int16Array): void {
    if (samples.length === 0) return;
    this.appendedSinceCommit += samples.length;
    this.send({ type: "input_audio_buffer.append", audio: int16ToBase64(samples) });
  }

  commit(): void {
    // Committing an empty buffer is an error upstream.
    if (this.appendedSinceCommit === 0) return;
    this.appendedSinceCommit = 0;
    this.commitsAwaiting++;
    this.send({ type: "input_audio_buffer.commit" });
  }

  async finish(timeoutMs: number): Promise<void> {
    this.commit();
    const deadline = Date.now() + timeoutMs;
    while (!this.settled() && !this.closed) {
      const left = deadline - Date.now();
      if (left <= 0) break;
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, left);
        this.settleWaiters.push(() => {
          clearTimeout(t);
          resolve();
        });
      });
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    try {
      this.ws.close(1000);
    } catch {}
    this.wake();
  }

  private sessionUpdate() {
    const transcription: Record<string, unknown> = {
      model: this.cfg.model,
      languages: this.opts.languages,
    };
    if (this.opts.keywords?.length) transcription.keywords = this.opts.keywords;
    if (this.opts.prompt) transcription.prompt = this.opts.prompt;
    if (this.cfg.delay) transcription.delay = this.cfg.delay;

    const input: Record<string, unknown> = {
      format: { type: "audio/pcm", rate: SAMPLE_RATE },
      transcription,
      turn_detection: null,
    };
    if (this.cfg.noiseReduction) input.noise_reduction = { type: this.cfg.noiseReduction };

    return { type: "session.update", session: { type: "transcription", audio: { input } } };
  }

  private handle(e: UpstreamEvent) {
    switch (e.type) {
      case "input_audio_buffer.committed":
        this.commitsAwaiting = Math.max(0, this.commitsAwaiting - 1);
        if (e.item_id) this.segmentOf(e.item_id);
        this.wake();
        break;
      case "conversation.item.input_audio_transcription.delta":
        if (e.item_id && e.delta) this.events.onDelta(this.segmentOf(e.item_id), e.delta);
        break;
      case "conversation.item.input_audio_transcription.completed":
        if (e.item_id) {
          this.completed.add(e.item_id);
          this.events.onFinal(this.segmentOf(e.item_id), e.transcript ?? "");
        }
        this.wake();
        break;
      case "conversation.item.input_audio_transcription.failed":
        if (e.item_id) this.completed.add(e.item_id);
        this.events.onError(e.error?.message ?? "transcription failed");
        this.wake();
        break;
      case "error":
        this.events.onError(e.error?.message ?? "OpenAI error");
        break;
    }
  }

  private segmentOf(itemId: string): number {
    let n = this.segments.get(itemId);
    if (n === undefined) {
      n = this.segments.size;
      this.segments.set(itemId, n);
    }
    return n;
  }

  private settled(): boolean {
    if (this.commitsAwaiting > 0) return false;
    for (const id of this.segments.keys()) if (!this.completed.has(id)) return false;
    return true;
  }

  private wake() {
    const waiters = this.settleWaiters;
    this.settleWaiters = [];
    for (const w of waiters) w();
  }

  private send(event: object) {
    if (this.closed) return;
    const msg = JSON.stringify(event);
    if (this.isReady) this.ws.send(msg);
    else this.queue.push(msg);
  }
}

function parse(data: unknown): UpstreamEvent | null {
  try {
    const text = typeof data === "string" ? data : Buffer.from(data as ArrayBuffer).toString("utf8");
    const e = JSON.parse(text);
    return e && typeof e.type === "string" ? e : null;
  } catch {
    return null;
  }
}
