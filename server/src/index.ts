import { activeModel, loadConfig } from "./config";
import { createServer } from "./server";

const cfg = loadConfig();
const server = createServer(cfg);
const model = activeModel(cfg);
const keyVar = cfg.provider === "groq" ? "GROQ_API_KEY" : "OPENAI_API_KEY";
console.log(`kudkeys server on :${server.port} — transcription: ${model ?? `not configured (set ${keyVar})`}`);
