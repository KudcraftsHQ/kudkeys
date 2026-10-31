import { GROQ_TRANSCRIPTIONS_URL } from "./providers/groq";
import { OPENAI_REALTIME_URL } from "./providers/openai";
import { GROQ_CHAT_URL } from "../text/groq-chat";

/**
 * Groq chat models for /api/text/* when KUDKEYS_TEXT_MODEL is unset, chosen with
 * server/scripts/eval-text.ts (2026-10-31): gpt-oss-120b fixes typos and follows
 * instructions best; qwen3.8-27b tidies dictation best (names fixed, useful
 * questions) at a third of the latency. Groq's limits are per model, so the
 * split also doubles the free tier's tokens per minute.
 */
export const DEFAULT_TEXT_MODEL = "openai/gpt-oss-120b";
export const DEFAULT_TIDY_MODEL = "qwen/qwen3.8-27b";

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
  /** Groq chat for /api/text/* (tidy, fix, instruct). Uses GROQ_API_KEY whatever the voice provider. */
  text: {
    apiKey: string | null;
    model: string;
    tidyModel: string;
    url: string;
    perMinute: number;
    /** Groq tokens per minute per model (free tier: 8,000); 0 = not counted. */
    tokensPerMinute: number;
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
    text: {
      apiKey: env.GROQ_API_KEY?.trim() || null,
      // KUDKEYS_TEXT_MODEL sets all three kinds; KUDKEYS_TIDY_MODEL then overrides tidy.
      model: env.KUDKEYS_TEXT_MODEL?.trim() || DEFAULT_TEXT_MODEL,
      tidyModel: env.KUDKEYS_TIDY_MODEL?.trim() || env.KUDKEYS_TEXT_MODEL?.trim() || DEFAULT_TIDY_MODEL,
      url: env.GROQ_CHAT_URL?.trim() || GROQ_CHAT_URL,
      perMinute: int(env.KUDKEYS_TEXT_RPM, 30),
      tokensPerMinute: env.KUDKEYS_TEXT_TPM === "0" ? 0 : int(env.KUDKEYS_TEXT_TPM, 8000),
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
