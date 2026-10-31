import { describe, expect, test } from "bun:test";
import { DEFAULT_SEGMENTER, Segmenter } from "../voice/segmenter";

const RATE = 16000;
const tone = (ms: number, amp = 5000) =>
  Int16Array.from({ length: (RATE * ms) / 1000 }, (_, i) => Math.round(amp * Math.sin((2 * Math.PI * 300 * i) / RATE)));
const quiet = (ms: number) => Int16Array.from({ length: (RATE * ms) / 1000 }, (_, i) => (i % 7) - 3); // faint hiss

function run(parts: Int16Array[], chunkMs = 100, opts = {}) {
  const phrases: { seconds: number; speechMs: number }[] = [];
  const s = new Segmenter({ ...DEFAULT_SEGMENTER, sampleRate: RATE, ...opts }, (samples, speechMs) =>
    phrases.push({ seconds: samples.length / RATE, speechMs }),
  );
  const chunk = (RATE * chunkMs) / 1000;
  for (const p of parts) for (let i = 0; i < p.length; i += chunk) s.push(p.subarray(i, i + chunk));
  return { s, phrases };
}

describe("Segmenter", () => {
  test("cuts at a pause and trims the silence around each phrase", () => {
    const { s, phrases } = run([quiet(1000), tone(1500), quiet(1000), tone(1000), quiet(300)]);
    s.flush();
    expect(phrases.length).toBe(2);
    // 300 ms pre-roll + 1.5 s speech + 200 ms tail
    expect(phrases[0]!.seconds).toBeCloseTo(2.0, 1);
    expect(phrases[0]!.speechMs).toBe(1500);
    expect(phrases[1]!.speechMs).toBe(1000);
  });

  test("a short gap inside a phrase doesn't split it", () => {
    const { s, phrases } = run([tone(1000), quiet(400), tone(1000)]);
    s.flush();
    expect(phrases.length).toBe(1);
  });

  test("silence alone produces nothing", () => {
    const { s, phrases } = run([quiet(5000)]);
    s.flush();
    expect(phrases.length).toBe(0);
  });

  test("a click shorter than minSpeechMs is dropped", () => {
    const { s, phrases } = run([quiet(500), tone(100), quiet(1500)], 20);
    s.flush();
    expect(phrases.length).toBe(0);
  });

  test("long talk without a pause is cut at maxSegmentMs", () => {
    const { s, phrases } = run([tone(6000)], 100, { maxSegmentMs: 2500 });
    s.flush();
    expect(phrases.length).toBe(3);
    expect(phrases[0]!.seconds).toBeCloseTo(2.5, 1);
  });

  test("current() exposes the phrase in progress", () => {
    const { s } = run([quiet(500), tone(1200)]);
    const cur = s.current();
    expect(cur?.speechMs).toBe(1200);
    expect(s.inPhrase).toBe(true);
  });
});
