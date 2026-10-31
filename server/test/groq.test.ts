// End to end with Groq (the default): a keyboard client → our server → a fake Groq transcription API.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { loadConfig } from "../voice/config";
import { parseResult } from "../voice/providers/groq";
import { createServer } from "../server";
import { connect, TOKEN } from "./client";

type Mode = "ok" | "silence" | "refuse" | "429-once";
type Call = { fields: Record<string, string>; rate: number; seconds: number; auth: string | null };

let fake: ReturnType<typeof Bun.serve>;
let calls: Call[] = [];
let mode: Mode = "ok";

beforeAll(() => {
  fake = Bun.serve({
    port: 0,
    async fetch(req) {
      // Something else on the machine probes local ports; only our POSTs count.
      if (req.method !== "POST" || !new URL(req.url).pathname.endsWith("/audio/transcriptions")) {
        return new Response("not found", { status: 404 });
      }
      const form = await req.formData();
      const file = form.get("file") as unknown as Blob;
      const wav = new DataView(await file.arrayBuffer());
      const rate = wav.getUint32(24, true);
      const seconds = wav.getUint32(40, true) / 2 / rate;
      const fields: Record<string, string> = {};
      for (const [k, v] of form.entries()) if (typeof v === "string") fields[k] = v;
      calls.push({ fields, rate, seconds, auth: req.headers.get("authorization") });

      if (mode === "refuse") return Response.json({ error: { message: "Invalid API Key" } }, { status: 401 });
      if (mode === "429-once" && calls.length === 1) {
        return Response.json({ error: { message: "slow down" } }, { status: 429, headers: { "retry-after": "0" } });
      }
      if (mode === "silence") {
        return Response.json({
          text: " Terima kasih telah menonton.",
          segments: [{ text: " Terima kasih telah menonton.", avg_logprob: -1.4, no_speech_prob: 0.8 }],
        });
      }
      const text = `phrase of ${seconds.toFixed(1)} s`;
      return Response.json({ text, segments: [{ text: ` ${text}`, avg_logprob: -0.105, no_speech_prob: 0.01 }] });
    },
  });
});

afterAll(() => fake.stop(true));

function startServer(extra: Record<string, string> = {}) {
  calls = [];
  return createServer(
    loadConfig({
      PORT: "0",
      KUDKEYS_TOKENS: TOKEN,
      GROQ_API_KEY: "gsk-test",
      GROQ_TRANSCRIPTIONS_URL: `http://localhost:${fake.port}/openai/v1/audio/transcriptions`,
      KUDKEYS_IDLE_SECONDS: "5",
      ...extra,
    }),
  );
}

// 16 kHz test audio as raw PCM16 bytes
const RATE = 16000;
const tone = (ms: number) =>
  Int16Array.from({ length: (RATE * ms) / 1000 }, (_, i) => Math.round(5000 * Math.sin((2 * Math.PI * 300 * i) / RATE)));
const quiet = (ms: number) => new Int16Array((RATE * ms) / 1000);
function sendAudio(ws: WebSocket, parts: Int16Array[]) {
  for (const p of parts)
    for (let i = 0; i < p.length; i += 1600) {
      const f = p.subarray(i, i + 1600);
      ws.send(new Uint8Array(f.buffer, f.byteOffset, f.byteLength));
    }
}

async function session(parts: Int16Array[], extra: Record<string, string> = {}, start: object = {}) {
  const server = startServer(extra);
  const c = connect(server.port!);
  await c.opened;
  c.ws.send(JSON.stringify({ type: "start", sampleRate: 16000, ...start }));
  await c.waitFor("ready");
  sendAudio(c.ws, parts);
  c.ws.send(JSON.stringify({ type: "stop" }));
  const code = await c.closed;
  server.stop(true);
  return { code, messages: c.messages };
}

describe("/v1/stream with Groq", () => {
  test("health names the Groq model", async () => {
    const server = startServer();
    expect(await (await fetch(`http://localhost:${server.port}/health`)).json()).toEqual({
      ok: true,
      transcription: "groq:whisper-large-v3",
    });
    server.stop(true);
  });

  test("two phrases separated by a pause become two finals, in order, with confidence", async () => {
    const { code, messages } = await session([quiet(500), tone(1500), quiet(1000), tone(1000)], {}, { keywords: ["Fenno", "TypeMap"] });
    expect(code).toBe(1000);
    // Phrases are transcribed in parallel, so finals can arrive out of order (the contract says to place them by
    // segment); under load the shorter second phrase sometimes comes back first.
    const finals = messages.filter((m) => m.type === "final").sort((a, b) => a.segment - b.segment);
    expect(finals.map((f) => f.segment)).toEqual([0, 1]);
    expect(finals[0].text).toBe("phrase of 2.0 s"); // 300 ms pre-roll + 1.5 s + 200 ms tail
    expect(finals[0].confidence).toBe(0.9);
    expect(messages.at(-1).type).toBe("done");

    expect(calls.length).toBe(2);
    expect(calls[0]!.rate).toBe(16000);
    expect(calls[0]!.auth).toBe("Bearer gsk-test");
    expect(calls[0]!.fields).toMatchObject({
      model: "whisper-large-v3",
      response_format: "verbose_json",
      temperature: "0",
      prompt: "Fenno, TypeMap",
    });
    // id + en → no language, so Whisper detects it per phrase
    expect(calls[0]!.fields.language).toBeUndefined();
  });

  test("a single language is passed through", async () => {
    await session([tone(1000)], {}, { languages: ["id"] });
    expect(calls[0]!.fields.language).toBe("id");
  });

  test("silence never reaches Groq", async () => {
    const { messages } = await session([quiet(3000)]);
    expect(calls.length).toBe(0);
    expect(messages.map((m) => m.type)).toEqual(["ready", "done"]);
  });

  test("Whisper's made-up text on near-silence is dropped", async () => {
    mode = "silence";
    const { messages } = await session([tone(800)]);
    mode = "ok";
    expect(calls.length).toBe(1);
    expect(messages.filter((m) => m.type === "final")).toEqual([]);
  });

  test("a refused key ends the session with a provider error", async () => {
    mode = "refuse";
    const { code, messages } = await session([tone(800)]);
    mode = "ok";
    expect(code).toBe(1011);
    expect(messages.find((m) => m.type === "error")).toMatchObject({ code: "provider" });
    expect(messages.find((m) => m.type === "error").message).toContain("Invalid API Key");
  });

  test("a final retries once after 429", async () => {
    mode = "429-once";
    const { messages } = await session([tone(800)]);
    mode = "ok";
    expect(calls.length).toBe(2);
    expect(messages.filter((m) => m.type === "final").length).toBe(1);
  });

  test("partials, when enabled, show the phrase in progress before its final", async () => {
    const server = startServer({ KUDKEYS_PARTIAL_MS: "100" });
    const c = connect(server.port!);
    await c.opened;
    c.ws.send(JSON.stringify({ type: "start", sampleRate: 16000 }));
    await c.waitFor("ready");
    sendAudio(c.ws, [tone(1500)]); // still talking: no pause yet
    await c.waitFor("partial");
    c.ws.send(JSON.stringify({ type: "stop" }));
    await c.closed;
    server.stop(true);
    const types = c.messages.map((m) => m.type);
    expect(types.indexOf("partial")).toBeLessThan(types.indexOf("final"));
    expect(c.messages.find((m) => m.type === "partial").segment).toBe(0);
    expect(c.messages.find((m) => m.type === "final").segment).toBe(0);
  });

  test("partials are off by default", async () => {
    const { messages } = await session([tone(1500)]);
    expect(messages.some((m) => m.type === "partial")).toBe(false);
  });
});

describe("parseResult", () => {
  test("keeps real speech and reports the weakest segment's confidence", () => {
    expect(
      parseResult({
        segments: [
          { text: " Halo tim,", avg_logprob: -0.1, no_speech_prob: 0.01 },
          { text: " besok meeting jam tiga.", avg_logprob: -0.5, no_speech_prob: 0.02 },
        ],
      }),
    ).toEqual({ text: "Halo tim, besok meeting jam tiga.", confidence: 0.61 });
  });
});
