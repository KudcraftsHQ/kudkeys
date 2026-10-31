// A test stand-in for the keyboard: connects to /v1/stream and records every server message.

export const TOKEN = "t".repeat(32);

export function connect(port: number, token = TOKEN) {
  const ws = new WebSocket(`ws://localhost:${port}/v1/stream`, {
    headers: { Authorization: `Bearer ${token}` },
  } as unknown as string[]);
  const messages: any[] = [];
  const closed = new Promise<number>((resolve) => ws.addEventListener("close", (e) => resolve(e.code)));
  ws.addEventListener("message", (e) => messages.push(JSON.parse(String(e.data))));
  const opened = new Promise<void>((resolve) => ws.addEventListener("open", () => resolve()));
  const waitFor = async (type: string, timeoutMs = 3000) => {
    for (let t = 0; t < timeoutMs; t += 10) {
      if (messages.some((m) => m.type === type)) return;
      await Bun.sleep(10);
    }
    throw new Error(`no ${type}; got ${JSON.stringify(messages)}`);
  };
  return { ws, messages, closed, opened, waitFor };
}
