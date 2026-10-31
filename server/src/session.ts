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

  constructor(
    private readonly socket: Socket,
    private readonly cfg: Config,
    private readonly makeTranscriber: TranscriberFactory | null,
  ) {
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
    this.inputRate = msg.sampleRate;
    this.transcriber = this.makeTranscriber(
      {
        languages: msg.languages ?? this.cfg.defaultLanguages,
        keywords: msg.keywords,
        prompt: msg.prompt,
      },
      {
        onPartial: (segment, text) => this.send({ type: "partial", segment, text }),
        onFinal: (segment, text, confidence) => {
          this.finals++;
          this.send(confidence === undefined ? { type: "final", segment, text } : { type: "final", segment, text, confidence });
        },
        onError: (message) => this.fail("provider", message),
      },
    );
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
      const audioSeconds = this.inputRate ? (this.samplesIn / this.inputRate).toFixed(1) : "0";
      console.log(
        `session ${this.id} ${this.outcome} audio=${audioSeconds}s finals=${this.finals} wall=${Date.now() - this.startedAt}ms`,
      );
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
