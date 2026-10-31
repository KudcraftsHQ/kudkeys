// PCM16 helpers. The keyboard records at whatever rate the phone likes (16, 24 or 48 kHz);
// OpenAI's realtime transcription only takes 24 kHz, so the server converts.

/**
 * Splits incoming bytes into whole 16-bit samples, carrying an odd trailing byte
 * over to the next chunk so a sample is never cut in half.
 */
export class SampleAligner {
  private carry: number | null = null;

  push(chunk: Uint8Array): Int16Array {
    let bytes = chunk;
    if (this.carry !== null) {
      const joined = new Uint8Array(chunk.length + 1);
      joined[0] = this.carry;
      joined.set(chunk, 1);
      bytes = joined;
      this.carry = null;
    }
    if (bytes.length % 2 === 1) {
      this.carry = bytes[bytes.length - 1]!;
      bytes = bytes.subarray(0, bytes.length - 1);
    }
    const out = new Int16Array(bytes.length / 2);
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    for (let i = 0; i < out.length; i++) out[i] = view.getInt16(i * 2, true);
    return out;
  }
}

/**
 * Linear-interpolation resampler that keeps its position across chunks,
 * so chunk boundaries don't add clicks or drift.
 */
export class Resampler {
  private readonly step: number;
  private pos = 0; // read position in the input stream, relative to the current chunk
  private last: number | null = null; // last sample of the previous chunk

  constructor(
    readonly fromRate: number,
    readonly toRate: number,
  ) {
    this.step = fromRate / toRate;
  }

  push(input: Int16Array): Int16Array {
    if (this.fromRate === this.toRate) return input;
    // Prepend the previous chunk's last sample so interpolation can cross the boundary.
    const src = this.last === null ? input : prepend(this.last, input);
    const offset = this.last === null ? 0 : 1;
    const out: number[] = [];
    let p = this.pos + offset;
    while (p <= src.length - 1) {
      const i = Math.floor(p);
      const frac = p - i;
      const a = src[i]!;
      const b = i + 1 < src.length ? src[i + 1]! : a;
      out.push(Math.round(a + (b - a) * frac));
      p += this.step;
    }
    if (input.length > 0) this.last = input[input.length - 1]!;
    // Position relative to the next chunk, whose index 0 will sit after `last`.
    this.pos = p - src.length;
    return Int16Array.from(out);
  }
}

function prepend(first: number, rest: Int16Array): Int16Array {
  const out = new Int16Array(rest.length + 1);
  out[0] = first;
  out.set(rest, 1);
  return out;
}

export function int16ToBase64(samples: Int16Array): string {
  const bytes = new Uint8Array(samples.length * 2);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < samples.length; i++) view.setInt16(i * 2, samples[i]!, true);
  return Buffer.from(bytes).toString("base64");
}
