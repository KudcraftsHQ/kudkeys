import { describe, expect, test } from "bun:test";
import { Resampler, SampleAligner } from "../voice/audio";

describe("SampleAligner", () => {
  test("carries an odd byte into the next chunk", () => {
    const a = new SampleAligner();
    // samples 1 and -2 as little-endian bytes, split mid-sample
    const first = a.push(new Uint8Array([0x01, 0x00, 0xfe]));
    const second = a.push(new Uint8Array([0xff]));
    expect(Array.from(first)).toEqual([1]);
    expect(Array.from(second)).toEqual([-2]);
  });
});

describe("Resampler", () => {
  const total = (r: Resampler, chunks: Int16Array[]) => chunks.reduce((n, c) => n + r.push(c).length, 0);

  test("16 kHz → 24 kHz keeps the duration across many chunks", () => {
    const r = new Resampler(16000, 24000);
    const chunks = Array.from({ length: 10 }, () => new Int16Array(1600)); // 10 × 100 ms
    const n = total(r, chunks);
    expect(Math.abs(n - 24000)).toBeLessThanOrEqual(2);
  });

  test("48 kHz → 24 kHz halves the samples", () => {
    const r = new Resampler(48000, 24000);
    const n = total(r, Array.from({ length: 10 }, () => new Int16Array(4800)));
    expect(Math.abs(n - 24000)).toBeLessThanOrEqual(2);
  });

  test("24 kHz passes through untouched", () => {
    const r = new Resampler(24000, 24000);
    const input = Int16Array.from([1, 2, 3]);
    expect(r.push(input)).toBe(input);
  });

  test("interpolates a ramp without jumps at chunk boundaries", () => {
    const r = new Resampler(16000, 24000);
    const ramp = Int16Array.from({ length: 3200 }, (_, i) => i * 4);
    const out = [...r.push(ramp.subarray(0, 1600)), ...r.push(ramp.subarray(1600))];
    for (let i = 1; i < out.length; i++) {
      const step = out[i]! - out[i - 1]!;
      expect(step).toBeGreaterThanOrEqual(2);
      expect(step).toBeLessThanOrEqual(3);
    }
  });
});
