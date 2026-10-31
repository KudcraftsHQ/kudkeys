import { loadConfig } from "./config";
import { createServer } from "./server";

const cfg = loadConfig();
const server = createServer(cfg);
console.log(
  `kudkeys server on :${server.port} — transcription: ${cfg.openai.apiKey ? cfg.openai.model : "not configured (set OPENAI_API_KEY)"}`,
);
