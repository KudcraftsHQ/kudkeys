// Streams a WAV file to the server the way the keyboard will: 100 ms frames, in real time.
// Prints the words as they arrive plus the two latencies that matter.
//
//   KUDKEYS_URL=ws://localhost:8080 KUDKEYS_TOKEN=… bun scripts/stream-wav.ts note.wav [--fast] [--keywords Fenno,TypeMap]
//
// The WAV must be 16-bit PCM at 16, 24 or 48 kHz (stereo is mixed down). Convert anything else:
//   ffmpeg -i note.m4a -ac 1 -ar 16000 -sample_fmt s16 note.wav

const [file, ...flags] = Bun.argv.slice(2);
if (!file) {
  console.error("usage: bun scripts/stream-wav.ts <file.wav> [--fast] [--keywords a,b]");
  process.exit(1);
}
const base = process.env.KUDKEYS_URL ?? "ws://localhost:8080";
const token = process.env.KUDKEYS_TOKEN;
if (!token) {
  console.error("set KUDKEYS_TOKEN");
  process.exit(1);
}
const fast = flags.includes("--fast");
const kwIndex = flags.indexOf("--keywords");
const keywords = kwIndex >= 0 ? flags[kwIndex + 1]?.split(",") : undefined;

const { rate, samples } = readWav(new Uint8Array(await Bun.file(file).arrayBuffer()));
const frame = rate / 10; // 100 ms

const ws = new WebSocket(`${base}/v1/stream`, { headers: { Authorization: `Bearer ${token}` } } as unknown as string[]);
const t0 = performance.now();
let firstWords = 0;
let stopAt = 0;
const segments: string[] = [];
const show = () => process.stdout.write(`\r\x1b[K${segments.filter(Boolean).join(" ").slice(-110)}`);

ws.addEventListener("message", async (e) => {
  const m = JSON.parse(String(e.data));
  if (m.type === "ready") {
    console.error(`ready (${m.model}) after ${ms(t0)}; streaming ${(samples.length / rate).toFixed(1)} s of audio`);
    for (let i = 0; i < samples.length; i += frame) {
      ws.send(toBytes(samples.subarray(i, i + frame)));
      if (!fast) await Bun.sleep(100);
    }
    stopAt = performance.now();
    ws.send(JSON.stringify({ type: "stop" }));
  } else if (m.type === "partial" || m.type === "final") {
    if (!firstWords) firstWords = performance.now();
    segments[m.segment] = m.type === "final" && m.confidence !== undefined ? `${m.text} [${m.confidence}]` : m.text;
    show();
  } else if (m.type === "error") {
    console.error(`\nerror ${m.code}: ${m.message}`);
  } else if (m.type === "done") {
    console.log(`\n\n--- final (phrase [confidence]) ---\n${segments.filter(Boolean).join("\n")}`);
    console.error(`first words after ${firstWords ? ms(t0, firstWords) : "—"}, last words ${ms(stopAt)} after stop`);
  }
});
ws.addEventListener("open", () => ws.send(JSON.stringify({ type: "start", sampleRate: rate, keywords })));
ws.addEventListener("close", (e) => process.exit(e.code === 1000 ? 0 : 1));

function ms(from: number, to = performance.now()) {
  return `${Math.round(to - from)} ms`;
}

function toBytes(s: Int16Array) {
  return new Uint8Array(s.buffer, s.byteOffset, s.byteLength);
}

function readWav(buf: Uint8Array): { rate: number; samples: Int16Array } {
  const v = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const tag = (o: number) => String.fromCharCode(...buf.subarray(o, o + 4));
  if (tag(0) !== "RIFF" || tag(8) !== "WAVE") throw new Error("not a WAV file");
  let o = 12;
  let rate = 0;
  let channels = 0;
  let bits = 0;
  while (o + 8 <= buf.length) {
    const id = tag(o);
    const size = v.getUint32(o + 4, true);
    if (id === "fmt ") {
      channels = v.getUint16(o + 10, true);
      rate = v.getUint32(o + 12, true);
      bits = v.getUint16(o + 22, true);
    } else if (id === "data") {
      if (bits !== 16) throw new Error(`need 16-bit PCM, got ${bits}-bit`);
      if (![16000, 24000, 48000].includes(rate)) throw new Error(`need 16/24/48 kHz, got ${rate} Hz (see ffmpeg line at the top)`);
      const frames = Math.floor(size / 2 / channels);
      const out = new Int16Array(frames);
      for (let i = 0; i < frames; i++) {
        let sum = 0;
        for (let c = 0; c < channels; c++) sum += v.getInt16(o + 8 + (i * channels + c) * 2, true);
        out[i] = Math.round(sum / channels);
      }
      return { rate, samples: out };
    }
    o += 8 + size + (size % 2);
  }
  throw new Error("no data chunk");
}

export {};
