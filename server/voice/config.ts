import { GROQ_TRANSCRIPTIONS_URL } from "./providers/groq";
import { OPENAI_REALTIME_URL } from "./providers/openai";

export type ProviderName = "groq" | "openai";

export type Config = {
  port: number;
  tokens: string[];
  provider: ProviderName;
  groq: {
    apiKey: string | null;
    model: string;
    url: string;
    partialMs: number;
    pauseMs: number;
    rpm: number;
  };
  openai: {
    apiKey: string | null;
    model: string;
    url: string;
    delay?: string;
    noiseReduction?: "near_field" | "far_field";
  };
  defaultLanguages: string[];
  maxSessionSeconds: number;
  idleSeconds: number;
  finishTimeoutMs: number;
};

export function loadConfig(env: Record<string, string | undefined> = process.env): Config {
  // Legacy static tokens. Optional now that accounts exist: a signed-in user's session token works too.
  const tokens = list(env.KUDKEYS_TOKENS);
  if (tokens.some((t) => t.length < 24)) throw new Error("every KUDKEYS_TOKENS entry must be at least 24 characters");

  const provider = (env.KUDKEYS_PROVIDER?.trim() || "groq") as ProviderName;
  if (provider !== "groq" && provider !== "openai") throw new Error("KUDKEYS_PROVIDER must be groq or openai");

  const noise = env.KUDKEYS_NOISE_REDUCTION;
  if (noise && noise !== "near_field" && noise !== "far_field") {
    throw new Error("KUDKEYS_NOISE_REDUCTION must be near_field or far_field");
  }

  return {
    port: env.PORT === "0" ? 0 : int(env.PORT, 8080), // 0 = any free port (tests)
    tokens,
    provider,
    groq: {
      apiKey: env.GROQ_API_KEY?.trim() || null,
      model: (provider === "groq" && env.KUDKEYS_MODEL?.trim()) || "whisper-large-v3",
      url: env.GROQ_TRANSCRIPTIONS_URL?.trim() || GROQ_TRANSCRIPTIONS_URL,
      partialMs: env.KUDKEYS_PARTIAL_MS === "0" ? 0 : int(env.KUDKEYS_PARTIAL_MS, 0),
      pauseMs: int(env.KUDKEYS_PAUSE_MS, 800),
      rpm: int(env.KUDKEYS_GROQ_RPM, 20),
    },
    openai: {
      apiKey: env.OPENAI_API_KEY?.trim() || null,
      model: (provider === "openai" && env.KUDKEYS_MODEL?.trim()) || "gpt-live-transcribe",
      url: env.OPENAI_REALTIME_URL?.trim() || OPENAI_REALTIME_URL,
      delay: env.KUDKEYS_DELAY?.trim() || undefined,
      noiseReduction: (noise as Config["openai"]["noiseReduction"]) || undefined,
    },
    defaultLanguages: list(env.KUDKEYS_LANGUAGES).length ? list(env.KUDKEYS_LANGUAGES) : ["id", "en"],
    maxSessionSeconds: int(env.KUDKEYS_MAX_SESSION_SECONDS, 600),
    idleSeconds: int(env.KUDKEYS_IDLE_SECONDS, 30),
    finishTimeoutMs: int(env.KUDKEYS_FINISH_TIMEOUT_MS, 15000),
  };
}

/** "groq:whisper-large-v3", or null when the chosen provider has no key. */
export function activeModel(cfg: Config): string | null {
  const p = cfg[cfg.provider];
  return p.apiKey ? `${cfg.provider}:${p.model}` : null;
}

function list(v: string | undefined): string[] {
  return (v ?? "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
}

function int(v: string | undefined, fallback: number): number {
  if (!v) return fallback;
  const n = Number.parseInt(v, 10);
  if (!Number.isFinite(n) || n <= 0) throw new Error(`expected a positive integer, got ${JSON.stringify(v)}`);
  return n;
}
