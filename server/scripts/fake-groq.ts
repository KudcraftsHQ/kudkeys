// A stand-in for Groq's transcription and chat endpoints, for smoke tests of
// the whole container (CI's image job). Transcription answers every POST with
// the same phrase; chat answers {"text": "Smoke test.", "changed": true}.
//   bun server/scripts/fake-groq.ts [port]   → GROQ_TRANSCRIPTIONS_URL=http://localhost:<port>/openai/v1/audio/transcriptions
//                                              GROQ_CHAT_URL=http://localhost:<port>/openai/v1/chat/completions

const port = Number(Bun.argv[2] ?? 18099)
Bun.serve({
  port,
  async fetch(req) {
    if (req.method !== 'POST') return new Response('not found', { status: 404 })
    if (new URL(req.url).pathname.endsWith('/chat/completions')) {
      await req.json()
      const content = JSON.stringify({ text: 'Smoke test.', changed: true, questions: [] })
      return Response.json({ choices: [{ message: { content }, finish_reason: 'stop' }] })
    }
    await req.formData()
    return Response.json({ text: 'smoke test', segments: [{ text: ' smoke test', avg_logprob: -0.1, no_speech_prob: 0.01 }] })
  },
})
console.log(`fake groq on :${port}`)
