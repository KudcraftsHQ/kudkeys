// Splits a live 16 kHz mic stream into phrases at pauses, for providers that only take whole
// files (Groq's Whisper). Energy-based: cheap, no model, good enough for a close-talking phone mic.
//
// It also trims silence, which matters for Whisper: fed silence, it invents text
// ("Terima kasih telah menonton").

export type SegmenterOptions = {
  sampleRate: number;
  /** A pause this long after speech ends the phrase. */
  pauseMs: number;
  /** A phrase is cut here even without a pause (Whisper's window is 30 s). */
  maxSegmentMs: number;
  /** Phrases with less speech than this are dropped as clicks or breaths. */
  minSpeechMs: number;
  /** Audio kept before the first speech frame, so onsets aren't clipped. */
  preRollMs: number;
  /** Silence kept after the last speech frame. */
  tailMs: number;
  /** Absolute floor for "speech" RMS on 16-bit samples. */
  minRms: number;
};

export const DEFAULT_SEGMENTER: Omit<SegmenterOptions, "sampleRate"> = {
  pauseMs: 800,
  maxSegmentMs: 25_000,
  minSpeechMs: 250,
  preRollMs: 300,
  tailMs: 200,
  minRms: 300,
};

const FRAME_MS = 20;

export class Segmenter {
  private readonly frameLen: number;
  private pending = new Int16Array(0); // samples not yet making a whole frame
  private preRoll: Int16Array[] = [];
  private frames: Int16Array[] = [];
  private speechFrames = 0;
  private silentRun = 0; // consecutive silent frames at the end of the current phrase
  private noiseFloor = 0;
  private active = false;
  private version = 0; // bumps whenever the current phrase grows, for partial scheduling

  constructor(
    private readonly opts: SegmenterOptions,
    private readonly onSegment: (samples: Int16Array, speechMs: number) => void,
  ) {
    this.frameLen = Math.round((opts.sampleRate * FRAME_MS) / 1000);
  }

  push(samples: Int16Array) {
    const all = concat([this.pending, samples]);
    let i = 0;
    for (; i + this.frameLen <= all.length; i += this.frameLen) this.frame(all.slice(i, i + this.frameLen));
    this.pending = all.slice(i);
  }

  /** End the current phrase now (keyboard "commit" or "stop"). */
  flush() {
    if (this.pending.length && this.active) this.frames.push(this.pending);
    this.pending = new Int16Array(0);
    this.end();
  }

  /** The phrase so far, for an in-progress (partial) transcription. Null when nothing worth sending. */
  current(): { samples: Int16Array; speechMs: number; version: number } | null {
    if (!this.active) return null;
    return { samples: concat(this.frames), speechMs: this.speechFrames * FRAME_MS, version: this.version };
  }

  get inPhrase() {
    return this.active;
  }

  private frame(f: Int16Array) {
    const rms = rmsOf(f);
    const threshold = Math.max(this.opts.minRms, this.noiseFloor * 2.5);
    const speech = rms > threshold;
    if (!speech) this.noiseFloor = this.noiseFloor === 0 ? rms : this.noiseFloor * 0.95 + rms * 0.05;

    if (!this.active) {
      if (speech) {
        this.active = true;
        this.frames = [...this.preRoll, f];
        this.preRoll = [];
        this.speechFrames = 1;
        this.silentRun = 0;
        this.version++;
      } else {
        this.preRoll.push(f);
        const keep = Math.ceil(this.opts.preRollMs / FRAME_MS);
        if (this.preRoll.length > keep) this.preRoll.shift();
      }
      return;
    }

    this.frames.push(f);
    if (speech) {
      this.speechFrames++;
      this.silentRun = 0;
      this.version++;
    } else {
      this.silentRun++;
    }
    if (this.silentRun * FRAME_MS >= this.opts.pauseMs || this.frames.length * FRAME_MS >= this.opts.maxSegmentMs) {
      this.end();
    }
  }

  private end() {
    if (!this.active) return;
    const keepTail = Math.ceil(this.opts.tailMs / FRAME_MS);
    const drop = Math.max(0, this.silentRun - keepTail);
    const frames = drop ? this.frames.slice(0, this.frames.length - drop) : this.frames;
    const speechMs = this.speechFrames * FRAME_MS;
    this.active = false;
    this.frames = [];
    this.speechFrames = 0;
    this.silentRun = 0;
    if (speechMs >= this.opts.minSpeechMs) this.onSegment(concat(frames), speechMs);
  }
}

function rmsOf(f: Int16Array): number {
  let sum = 0;
  for (let i = 0; i < f.length; i++) sum += f[i]! * f[i]!;
  return Math.sqrt(sum / f.length);
}

export function concat(parts: Int16Array[]): Int16Array {
  let n = 0;
  for (const p of parts) n += p.length;
  const out = new Int16Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

/** A 16-bit mono WAV file around raw samples. */
export function wavFile(samples: Int16Array, sampleRate: number): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(44 + samples.length * 2);
  const v = new DataView(out.buffer);
  const tag = (o: number, s: string) => {
    for (let i = 0; i < 4; i++) v.setUint8(o + i, s.charCodeAt(i));
  };
  tag(0, "RIFF");
  v.setUint32(4, 36 + samples.length * 2, true);
  tag(8, "WAVE");
  tag(12, "fmt ");
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); // PCM
  v.setUint16(22, 1, true); // mono
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * 2, true);
  v.setUint16(32, 2, true);
  v.setUint16(34, 16, true);
  tag(36, "data");
  v.setUint32(40, samples.length * 2, true);
  for (let i = 0; i < samples.length; i++) v.setInt16(44 + i * 2, samples[i]!, true);
  return out;
}
