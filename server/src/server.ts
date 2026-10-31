import { authorized } from "./auth";
import type { Config } from "./config";
import { OpenAITranscriber } from "./providers/openai";
import type { TranscriberFactory } from "./providers/types";
import { VoiceSession } from "./session";

export function openAIFactory(cfg: Config): TranscriberFactory | null {
  const { apiKey, ...rest } = cfg.openai;
  if (!apiKey) return null;
  return (opts, events) => new OpenAITranscriber({ apiKey, ...rest }, opts, events);
}

export function createServer(cfg: Config, factory: TranscriberFactory | null = openAIFactory(cfg)) {
  return Bun.serve<{ session: VoiceSession | null }>({
    port: cfg.port,
    fetch(req, server) {
      const url = new URL(req.url);
      if (url.pathname === "/health") {
        return Response.json({ ok: true, transcription: factory ? cfg.openai.model : null });
      }
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
