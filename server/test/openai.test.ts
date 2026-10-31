// End to end with KUDKEYS_PROVIDER=openai: a keyboard client → our server → a fake OpenAI realtime server.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { loadConfig } from "../src/config";
import { createServer } from "../src/server";
import { connect, TOKEN } from "./client";


type Received = { session?: any; appendedBytes: number; commits: number; headers?: Headers };
let fake: ReturnType<typeof Bun.serve>;
let received: Received;
let fakeMode: "ok" | "refuse" = "ok";

beforeAll(() => {
  fake = Bun.serve<{ items: number; bytes: number }>({
    port: 0,
    fetch(req, server) {
      received.headers = req.headers;
      if (server.upgrade(req, { data: { items: 0, bytes: 0 } })) return undefined;
      return new Response("no", { status: 400 });
    },
    websocket: {
      message(ws, raw) {
        const e = JSON.parse(String(raw));
        if (e.type === "session.update") {
          received.session = e.session;
          if (fakeMode === "refuse") {
            ws.send(JSON.stringify({ type: "error", error: { message: "invalid model" } }));
            return;
          }
          ws.send(JSON.stringify({ type: "session.updated", session: e.session }));
        } else if (e.type === "input_audio_buffer.append") {
          const n = Buffer.from(e.audio, "base64").length;
          received.appendedBytes += n;
          ws.data.bytes += n;
        } else if (e.type === "input_audio_buffer.commit") {
          received.commits++;
          const id = `item_${ws.data.items++}`;
          const bytes = ws.data.bytes;
          ws.data.bytes = 0;
          ws.send(JSON.stringify({ type: "input_audio_buffer.committed", item_id: id }));
          ws.send(JSON.stringify({ type: "conversation.item.input_audio_transcription.delta", item_id: id, delta: "halo " }));
          setTimeout(() => {
            ws.send(
              JSON.stringify({
                type: "conversation.item.input_audio_transcription.completed",
                item_id: id,
                transcript: `halo ${bytes}`,
              }),
            );
          }, 30);
        }
      },
    },
  });
});

afterAll(() => fake.stop(true));

function startServer(extra: Record<string, string> = {}) {
  received = { appendedBytes: 0, commits: 0 };
  const cfg = loadConfig({
    PORT: "0",
    KUDKEYS_TOKENS: TOKEN,
    KUDKEYS_PROVIDER: "openai",
    OPENAI_API_KEY: "sk-test",
    OPENAI_REALTIME_URL: `ws://localhost:${fake.port}/v1/realtime?intent=transcription`,
    KUDKEYS_IDLE_SECONDS: "5",
    ...extra,
  });
  return createServer(cfg);
}

describe("/v1/stream with OpenAI", () => {
  test("rejects a missing or wrong token", async () => {
    const server = startServer();
    const res = await fetch(`http://localhost:${server.port}/v1/stream`, {
      headers: { Authorization: "Bearer nope", Upgrade: "websocket" },
    });
    expect(res.status).toBe(401);
    server.stop(true);
  });

  test("streams audio, resamples to 24 kHz, and returns deltas, finals and done", async () => {
    const server = startServer();
    const c = connect(server.port!);
    await c.opened;
    c.ws.send(JSON.stringify({ type: "start", sampleRate: 16000, keywords: ["Fenno", "Kud<crafts>"] }));
    await c.waitFor("ready");

    // 1 s of 16 kHz audio in 100 ms frames, a pause commit, then 0.5 s more.
    for (let i = 0; i < 10; i++) c.ws.send(new Uint8Array(3200));
    c.ws.send(JSON.stringify({ type: "commit" }));
    for (let i = 0; i < 5; i++) c.ws.send(new Uint8Array(3200));
    c.ws.send(JSON.stringify({ type: "stop" }));

    expect(await c.closed).toBe(1000);
    const types = c.messages.map((m) => m.type);
    expect(types[0]).toBe("ready");
    expect(types.at(-1)).toBe("done");

    const finals = c.messages.filter((m) => m.type === "final");
    expect(finals.map((f) => f.segment)).toEqual([0, 1]);
    const partials = c.messages.filter((m) => m.type === "partial");
    expect(partials.map((d) => d.segment)).toEqual([0, 1]);
    expect(partials[0].text).toBe("halo");

    // 1.5 s at 24 kHz × 2 bytes ≈ 72 000 bytes, split across the two commits.
    expect(Math.abs(received.appendedBytes - 72000)).toBeLessThanOrEqual(8);
    expect(received.commits).toBe(2);

    // What the provider was told.
    expect(received.headers?.get("authorization")).toBe("Bearer sk-test");
    expect(received.session.type).toBe("transcription");
    expect(received.session.audio.input.format).toEqual({ type: "audio/pcm", rate: 24000 });
    expect(received.session.audio.input.turn_detection).toBeNull();
    expect(received.session.audio.input.transcription.model).toBe("gpt-live-transcribe");
    expect(received.session.audio.input.transcription.languages).toEqual(["id", "en"]);
    expect(received.session.audio.input.transcription.keywords).toEqual(["Fenno", "Kud crafts"]);
    server.stop(true);
  });

  test("stop with no new audio does not send an empty commit", async () => {
    const server = startServer();
    const c = connect(server.port!);
    await c.opened;
    c.ws.send(JSON.stringify({ type: "start", sampleRate: 24000 }));
    await c.waitFor("ready");
    c.ws.send(JSON.stringify({ type: "stop" }));
    expect(await c.closed).toBe(1000);
    expect(received.commits).toBe(0);
    expect(c.messages.map((m) => m.type)).toEqual(["ready", "done"]);
    server.stop(true);
  });

  test("audio before start is an error", async () => {
    const server = startServer();
    const c = connect(server.port!);
    await c.opened;
    c.ws.send(new Uint8Array(100));
    expect(await c.closed).toBe(1008);
    expect(c.messages[0]).toMatchObject({ type: "error", code: "not_started" });
    server.stop(true);
  });

  test("a provider refusal reaches the keyboard as a provider error", async () => {
    fakeMode = "refuse";
    const server = startServer();
    const c = connect(server.port!);
    await c.opened;
    c.ws.send(JSON.stringify({ type: "start", sampleRate: 16000 }));
    expect(await c.closed).toBe(1011);
    expect(c.messages[0]).toMatchObject({ type: "error", code: "provider", message: "invalid model" });
    fakeMode = "ok";
    server.stop(true);
  });

  test("the session cap ends the stream gracefully with finals", async () => {
    const server = startServer({ KUDKEYS_MAX_SESSION_SECONDS: "1" });
    const c = connect(server.port!);
    await c.opened;
    c.ws.send(JSON.stringify({ type: "start", sampleRate: 16000 }));
    await c.waitFor("ready");
    for (let i = 0; i < 12; i++) c.ws.send(new Uint8Array(3200)); // 1.2 s
    expect(await c.closed).toBe(1000);
    const types = c.messages.map((m) => m.type);
    expect(types).toContain("final");
    expect(c.messages.find((m) => m.type === "error")).toMatchObject({ code: "limit" });
    expect(types.at(-1)).toBe("done");
    server.stop(true);
  });

  test("without an OpenAI key, start fails clearly and /health says so", async () => {
    const server = startServer({ OPENAI_API_KEY: "" });
    const health = await (await fetch(`http://localhost:${server.port}/health`)).json();
    expect(health).toEqual({ ok: true, transcription: null });
    const c = connect(server.port!);
    await c.opened;
    c.ws.send(JSON.stringify({ type: "start", sampleRate: 16000 }));
    expect(await c.closed).toBe(1011);
    expect(c.messages[0]).toMatchObject({ type: "error", code: "provider" });
    server.stop(true);
  });
});
