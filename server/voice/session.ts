// One keyboard voice session: bridges the keyboard's socket to a provider stream.
// Logs only counts and timings. Transcript text is never logged or stored.

import { Resampler, SampleAligner } from "./audio";
import type { Config } from "./config";
import { parseClientMessage, type ErrorCode, type ServerMessage, type StartMessage } from "./protocol";
import type { StreamingTranscriber, TranscriberFactory } from "./providers/types";

export type Socket = {
  send(data: string): unknown;
  close(code?: number, reason?: string): void;
};

type State = "waiting" | "streaming" | "finishing" | "closed";

/** Who opened the stream: a signed-in user (with their voice profile) or a legacy static token. */
export type StreamAuth =
  | { kind: "user"; userId: string; voiceKeywords: string[]; languages: string[] }
  | { kind: "token" };

/** What a finished session reports. Numbers only, never text. */
export type SessionSummary = {
  session: string;
  startedAt: Date;
  endedAt: Date;
  audioSeconds: number;
  finals: number;
  outcome: string;
  model: string | null;
};

export type SessionOptions = {
  auth?: StreamAuth;
  /** Called once when a session that got past `start` ends. */
  onEnd?: (summary: SessionSummary) => void;
};

const MAX_KEYWORDS = 100;

/** The keyboard's keywords first (they're about this field), then the profile's, without duplicates. */
export function mergeKeywords(fromStart: string[] | undefined, fromProfile: string[]): string[] | undefined {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const k of [...(fromStart ?? []), ...fromProfile]) {
    const key = k.trim().toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    out.push(k.trim());
    if (out.length === MAX_KEYWORDS) break;
  }
  return out.length ? out : fromStart;
}

export class VoiceSession {
  readonly id = crypto.randomUUID().slice(0, 8);
  private state: State = "waiting";
  private transcriber: StreamingTranscriber | null = null;
  private aligner = new SampleAligner();
  private resampler: Resampler | null = null;
  private inputRate = 0;
  private samplesIn = 0;
  private finals = 0;
  private startedAt = Date.now();
  private idleTimer: ReturnType<typeof setTimeout> | null = null;
  private outcome = "open";
  private started = false;
  private model: string | null = null;
  private readonly auth: StreamAuth;

  constructor(
    private readonly socket: Socket,
    private readonly cfg: Config,
    private readonly makeTranscriber: TranscriberFactory | null,
    private readonly opts: SessionOptions = {},
  ) {
    this.auth = opts.auth ?? { kind: "token" };
    this.touch();
  }

  onText(raw: string) {
    this.touch();
    let msg;
    try {
      msg = parseClientMessage(raw);
    } catch (e) {
      return this.fail("bad_request", (e as Error).message);
    }
    switch (msg.type) {
      case "start":
        return void this.start(msg);
      case "commit":
        if (this.state !== "streaming") return this.fail("not_started", "send start first");
        this.transcriber!.commit();
        return;
      case "stop":
        if (this.state !== "streaming") return this.fail("not_started", "send start first");
        return void this.finish("stopped");
      case "cancel":
        this.outcome = "cancelled";
        return this.end(1000);
    }
  }

  onAudio(chunk: Uint8Array) {
    this.touch();
    if (this.state === "waiting") return this.fail("not_started", "send start before audio");
    if (this.state !== "streaming") return; // late frames after stop are dropped
    const samples = this.aligner.push(chunk);
    this.samplesIn += samples.length;
    this.transcriber!.appendAudio(this.resampler!.push(samples));
    if (this.samplesIn / this.inputRate > this.cfg.maxSessionSeconds) {
      this.send({ type: "error", code: "limit", message: `sessions are capped at ${this.cfg.maxSessionSeconds} s` });
      void this.finish("limit");
    }
  }

  /** The keyboard's socket closed. */
  onClose() {
    if (this.state !== "closed" && this.outcome === "open") this.outcome = "client closed";
    this.state = "closed";
    this.cleanup();
  }

  private async start(msg: StartMessage) {
    if (this.state !== "waiting") return this.fail("bad_request", "already started");
    if (!this.makeTranscriber) return this.fail("provider", "transcription provider is not configured on the server");
    this.state = "streaming";
    this.started = true;
    this.inputRate = msg.sampleRate;
    const user = this.auth.kind === "user" ? this.auth : null;
    const languages = msg.languages ?? (user?.languages.length ? user.languages : this.cfg.defaultLanguages);
    const keywords = user ? mergeKeywords(msg.keywords, user.voiceKeywords) : msg.keywords;
    this.transcriber = this.makeTranscriber(
      { languages, keywords, prompt: msg.prompt },
      {
        onPartial: (segment, text) => this.send({ type: "partial", segment, text }),
        onFinal: (segment, text, confidence) => {
          this.finals++;
          this.send(confidence === undefined ? { type: "final", segment, text } : { type: "final", segment, text, confidence });
        },
        onError: (message) => this.fail("provider", message),
      },
    );
    this.model = this.transcriber.model;
    this.resampler = new Resampler(msg.sampleRate, this.transcriber.sampleRate);
    try {
      await this.transcriber.ready();
    } catch (e) {
      return this.fail("provider", (e as Error).message);
    }
    if (this.state === "streaming") this.send({ type: "ready", session: this.id, model: this.transcriber.model });
  }

  private async finish(outcome: string) {
    if (this.state !== "streaming") return;
    this.state = "finishing";
    this.outcome = outcome;
    try {
      await this.transcriber!.finish(this.cfg.finishTimeoutMs);
    } catch {}
    if (this.state === "finishing") {
      this.send({ type: "done" });
      this.end(1000);
    }
  }

  private fail(code: ErrorCode, message: string) {
    if (this.state === "closed") return;
    this.send({ type: "error", code, message });
    this.outcome = `error ${code}`;
    this.end(code === "provider" || code === "internal" ? 1011 : 1008);
  }

  private end(code: number) {
    if (this.state === "closed") return;
    this.state = "closed";
    this.cleanup();
    try {
      this.socket.close(code);
    } catch {}
  }

  private cleanup() {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.idleTimer = null;
    this.transcriber?.close();
    if (this.outcome !== "logged") {
      const audioSeconds = this.inputRate ? this.samplesIn / this.inputRate : 0;
      // The auth kind, never the token or who it belongs to.
      console.log(
        `session ${this.id} ${this.outcome} auth=${this.auth.kind} audio=${audioSeconds.toFixed(1)}s finals=${this.finals} wall=${Date.now() - this.startedAt}ms`,
      );
      if (this.started && this.opts.onEnd) {
        try {
          this.opts.onEnd({
            session: this.id,
            startedAt: new Date(this.startedAt),
            endedAt: new Date(),
            audioSeconds: Math.round(audioSeconds * 10) / 10,
            finals: this.finals,
            outcome: this.outcome,
            model: this.model,
          });
        } catch {}
      }
      this.outcome = "logged";
    }
  }

  private touch() {
    if (this.idleTimer) clearTimeout(this.idleTimer);
    this.idleTimer = setTimeout(() => {
      if (this.state === "streaming") void this.finish("idle");
      else if (this.state === "waiting") {
        this.outcome = "idle before start";
        this.end(1000);
      }
    }, this.cfg.idleSeconds * 1000);
  }

  private send(msg: ServerMessage) {
    if (this.state === "closed") return;
    try {
      this.socket.send(JSON.stringify(msg));
    } catch {}
  }
}
