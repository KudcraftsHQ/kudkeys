import { authorized } from "./auth";
import { activeModel, type Config } from "./config";
import { GroqTranscriber } from "./providers/groq";
import { OpenAITranscriber } from "./providers/openai";
import type { TranscriberFactory } from "./providers/types";
import { VoiceSession } from "./session";

/** The configured provider, or null when its key is missing. */
export function providerFactory(cfg: Config): TranscriberFactory | null {
  if (cfg.provider === "groq") {
    const { apiKey, ...rest } = cfg.groq;
    if (!apiKey) return null;
    return (opts, events) => new GroqTranscriber({ apiKey, ...rest }, opts, events);
  }
  const { apiKey, ...rest } = cfg.openai;
  if (!apiKey) return null;
  return (opts, events) => new OpenAITranscriber({ apiKey, ...rest }, opts, events);
}

export function createServer(cfg: Config, factory: TranscriberFactory | null = providerFactory(cfg)) {
  const model = factory ? activeModel(cfg) : null;
  return Bun.serve<{ session: VoiceSession | null }>({
    port: cfg.port,
    fetch(req, server) {
      const url = new URL(req.url);
      if (url.pathname === "/health") return Response.json({ ok: true, transcription: model });
      if (url.pathname === "/v1/stream") {
        if (!authorized(req, cfg.tokens)) return new Response("unauthorized\n", { status: 401 });
        if (server.upgrade(req, { data: { session: null } })) return undefined;
        return new Response("expected a WebSocket upgrade\n", { status: 426 });
      }
      return new Response("not found\n", { status: 404 });
    },
    websocket: {
      maxPayloadLength: 1024 * 1024,
      idleTimeout: 60,
      open(ws) {
        ws.data.session = new VoiceSession(ws, cfg, factory);
      },
      message(ws, message) {
        const s = ws.data.session!;
        if (typeof message === "string") s.onText(message);
        else s.onAudio(new Uint8Array(message));
      },
      close(ws) {
        ws.data.session?.onClose();
      },
    },
  });
}
