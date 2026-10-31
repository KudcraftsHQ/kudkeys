// The wire protocol between the keyboard and this server. It is ours, not the provider's,
// so the phone never changes when the transcription provider does.
//
// Keyboard → server
//   text   {"type":"start", sampleRate, languages?, keywords?, prompt?, private?}
//   binary PCM16 little-endian mono audio at `sampleRate`, any chunk size (~100 ms is ideal)
//   text   {"type":"commit"}   end the current phrase now; optional (the server also cuts at pauses)
//   text   {"type":"stop"}     finish: commit what is left, send the last finals, then "done"
//   text   {"type":"cancel"}   drop everything and close
//
// Server → keyboard
//   {"type":"ready", session, model}
//   {"type":"partial", segment, text}       the phrase so far; replaces the previous partial
//   {"type":"final", segment, text, confidence?}  the phrase, settled; confidence is 0–1 when known
//   Segments (phrases) are numbered in speaking order; a number may be skipped when a phrase
//   turned out to be silence.
//   {"type":"done"}                   every final has been sent; the socket closes next
//   {"type":"error", code, message}

export const SUPPORTED_RATES = [16000, 24000, 48000] as const;
export type SampleRate = (typeof SUPPORTED_RATES)[number];

export type StartMessage = {
  type: "start";
  sampleRate: SampleRate;
  languages?: string[];
  keywords?: string[];
  prompt?: string;
  /** Private field (incognito, search, URL). Nothing is kept or learned; today the server keeps nothing anyway. */
  private?: boolean;
};

export type ClientMessage =
  | StartMessage
  | { type: "commit" }
  | { type: "stop" }
  | { type: "cancel" };

export type ServerMessage =
  | { type: "ready"; session: string; model: string }
  | { type: "partial"; segment: number; text: string }
  | { type: "final"; segment: number; text: string; confidence?: number }
  | { type: "done" }
  | { type: "error"; code: ErrorCode; message: string };

export type ErrorCode =
  | "bad_request"
  | "not_started"
  | "provider"
  | "limit"
  | "internal";

const MAX_KEYWORDS = 100;
const MAX_KEYWORD_LENGTH = 100;
const MAX_PROMPT_LENGTH = 2000;

/** Parse and validate a text frame from the keyboard. Throws a message suitable for the client. */
export function parseClientMessage(raw: string): ClientMessage {
  let msg: unknown;
  try {
    msg = JSON.parse(raw);
  } catch {
    throw new Error("not JSON");
  }
  if (!msg || typeof msg !== "object") throw new Error("expected an object");
  const m = msg as Record<string, unknown>;
  switch (m.type) {
    case "commit":
    case "stop":
    case "cancel":
      return { type: m.type };
    case "start":
      return parseStart(m);
    default:
      throw new Error(`unknown type ${JSON.stringify(m.type)}`);
  }
}

function parseStart(m: Record<string, unknown>): StartMessage {
  if (!SUPPORTED_RATES.includes(m.sampleRate as SampleRate)) {
    throw new Error(`sampleRate must be one of ${SUPPORTED_RATES.join(", ")}`);
  }
  const out: StartMessage = { type: "start", sampleRate: m.sampleRate as SampleRate };

  if (m.languages !== undefined) {
    if (!isStringArray(m.languages) || m.languages.length === 0) {
      throw new Error("languages must be a non-empty array of language codes");
    }
    out.languages = m.languages;
  }
  if (m.keywords !== undefined) {
    if (!isStringArray(m.keywords) || m.keywords.length > MAX_KEYWORDS) {
      throw new Error(`keywords must be an array of at most ${MAX_KEYWORDS} strings`);
    }
    // The provider rejects keywords with angle brackets or line breaks.
    out.keywords = m.keywords
      .map((k) => k.replace(/[<>\r\n]/g, " ").trim())
      .filter((k) => k.length > 0 && k.length <= MAX_KEYWORD_LENGTH);
  }
  if (m.prompt !== undefined) {
    if (typeof m.prompt !== "string" || m.prompt.length > MAX_PROMPT_LENGTH) {
      throw new Error(`prompt must be a string of at most ${MAX_PROMPT_LENGTH} characters`);
    }
    out.prompt = m.prompt;
  }
  if (m.private !== undefined) out.private = m.private === true;
  return out;
}

function isStringArray(v: unknown): v is string[] {
  return Array.isArray(v) && v.every((x) => typeof x === "string");
}
