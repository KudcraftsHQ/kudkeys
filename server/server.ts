// One Bun server: the WebSocket at /v1/stream, and everything else handed to
// the Hono app (app.ts). The stream keeps its protocol and guarantees
// (voice/protocol.ts, README): transcript text is never logged or stored.

import type { SessionUser } from './app'
import { isAllowed } from './lib/allowlist'
import type { Store } from './lib/store'
import { activeModel, type Config } from './voice/config'
import { GroqTranscriber } from './voice/providers/groq'
import { OpenAITranscriber } from './voice/providers/openai'
import type { TranscriberFactory } from './voice/providers/types'
import { VoiceSession, type StreamAuth } from './voice/session'
import { authorized } from './voice/tokens'

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

export type StreamAuthenticator = (req: Request) => Promise<StreamAuth | null>;

/**
 * `Authorization: Bearer <x>` where x is a legacy KUDKEYS_TOKENS entry, or a
 * Better Auth session token of an allowed user (whose voice profile comes along).
 */
export function streamAuthenticator(deps: {
  tokens: string[];
  getSession?: (headers: Headers) => Promise<SessionUser | null>;
  store?: Store;
  allowlist?: Set<string>;
}): StreamAuthenticator {
  return async (req) => {
    if (!/^Bearer\s+\S/i.test(req.headers.get("authorization") ?? "")) return null;
    if (authorized(req, deps.tokens)) return { kind: "token" };
    if (!deps.getSession || !deps.store || !deps.allowlist) return null;
    const user = await deps.getSession(req.headers).catch(() => null);
    if (!user || !isAllowed(deps.allowlist, user.email)) return null;
    const profile = await deps.store.getProfile(user.id);
    if (!profile) return null;
    return { kind: "user", userId: user.id, voiceKeywords: profile.voiceKeywords, languages: profile.languages };
  };
}

export type ServerDeps = {
  factory?: TranscriberFactory | null;
  /** Handles everything that isn't /v1/stream (the Hono app). Omitted: only /health. */
  app?: { fetch: (req: Request) => Response | Promise<Response> };
  authenticate?: StreamAuthenticator;
  /** Where usage rows go for user sessions. */
  store?: Store;
};

type SocketData = { session: VoiceSession | null; auth: StreamAuth };

export function createServer(cfg: Config, deps: ServerDeps = {}) {
  const factory = deps.factory === undefined ? providerFactory(cfg) : deps.factory;
  const model = factory ? activeModel(cfg) : null;
  const authenticate = deps.authenticate ?? streamAuthenticator({ tokens: cfg.tokens });
  const store = deps.store;
  return Bun.serve<SocketData>({
    port: cfg.port,
    // 0 = no HTTP idle timeout; WebSockets have their own (below).
    idleTimeout: 0,
    async fetch(req, server) {
      const url = new URL(req.url);
      if (url.pathname === "/v1/stream") {
        const auth = await authenticate(req);
        if (!auth) return new Response("unauthorized\n", { status: 401 });
        if (server.upgrade(req, { data: { session: null, auth } })) return undefined;
        return new Response("expected a WebSocket upgrade\n", { status: 426 });
      }
      if (deps.app) return deps.app.fetch(req);
      if (url.pathname === "/health" || url.pathname === "/api/health") {
        return Response.json({ ok: true, transcription: model });
      }
      return new Response("not found\n", { status: 404 });
    },
    websocket: {
      maxPayloadLength: 1024 * 1024,
      idleTimeout: 60,
      open(ws) {
        const auth = ws.data.auth;
        ws.data.session = new VoiceSession(ws, cfg, factory, {
          auth,
          // Usage numbers for signed-in users, private fields included: the row holds no text.
          onEnd:
            auth.kind === "user" && store
              ? (summary) => {
                  store.recordVoiceSession(auth.userId, summary).catch((e) => {
                    console.error(`session ${summary.session} usage row not written: ${(e as Error).message}`);
                  });
                }
              : undefined,
        });
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
