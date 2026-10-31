// A stand-in for Groq's transcription endpoint, for smoke tests of the whole
// container (CI's image job). Answers every POST with the same phrase.
//   bun server/scripts/fake-groq.ts [port]   → GROQ_TRANSCRIPTIONS_URL=http://localhost:<port>/openai/v1/audio/transcriptions

const port = Number(Bun.argv[2] ?? 18099)
Bun.serve({
  port,
  async fetch(req) {
    if (req.method !== 'POST') return new Response('not found', { status: 404 })
    await req.formData()
    return Response.json({ text: 'smoke test', segments: [{ text: ' smoke test', avg_logprob: -0.1, no_speech_prob: 0.01 }] })
  },
})
console.log(`fake groq on :${port}`)
