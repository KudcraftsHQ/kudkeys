// Smoke test of a running server (e.g. the Docker image): sign up an allowed
// email, read /api/me, stream two seconds of tone with the session token and
// see the usage row. Never point it at production: it creates an account.
//
//   bun server/scripts/smoke.ts http://localhost:8080 hammas@example.com

const [base = 'http://localhost:8080', email = 'smoke@example.com'] = Bun.argv.slice(2)
const password = 'smoke-test-password-1'
const fail = (msg: string): never => {
  console.error(`smoke: FAIL ${msg}`)
  process.exit(1)
}
const json = { 'content-type': 'application/json' }

const health = await fetch(`${base}/api/health`).then((r) => r.json())
console.log('health', JSON.stringify(health))

let res = await fetch(`${base}/api/auth/sign-up/email`, { method: 'POST', headers: json, body: JSON.stringify({ email, password, name: 'Smoke' }) })
if (res.status !== 200 && res.status !== 422) fail(`sign-up ${res.status} ${await res.text()}`)
res = await fetch(`${base}/api/auth/sign-in/email`, { method: 'POST', headers: json, body: JSON.stringify({ email, password }) })
const token = res.headers.get('set-auth-token') ?? fail(`sign-in ${res.status}: no set-auth-token`)
const auth = { authorization: `Bearer ${token}` }

res = await fetch(`${base}/api/me`, { method: 'PATCH', headers: { ...json, ...auth }, body: JSON.stringify({ voiceKeywords: ['Fenno', 'TypeMap'] }) })
const me = (await res.json()) as { email: string; voiceKeywords: string[] }
if (me.email !== email || me.voiceKeywords.length !== 2) fail(`/api/me ${JSON.stringify(me)}`)
console.log('me', JSON.stringify(me))

const before = ((await (await fetch(`${base}/api/me/usage`, { headers: auth })).json()) as { sessions: unknown[] }).sessions.length
const ws = new WebSocket(`${base.replace(/^http/, 'ws')}/v1/stream`, { headers: auth } as unknown as string[])
const finals: string[] = []
const code = await new Promise<number>((resolve) => {
  ws.addEventListener('open', () => ws.send(JSON.stringify({ type: 'start', sampleRate: 16000 })))
  ws.addEventListener('message', (e) => {
    const m = JSON.parse(String(e.data))
    if (m.type === 'ready') {
      const tone = Int16Array.from({ length: 32000 }, (_, i) => Math.round(5000 * Math.sin((2 * Math.PI * 300 * i) / 16000)))
      for (let i = 0; i < tone.length; i += 1600) ws.send(tone.slice(i, i + 1600).buffer)
      ws.send(JSON.stringify({ type: 'stop' }))
    } else if (m.type === 'final') finals.push(m.text)
    else if (m.type === 'error') console.error('stream error', m)
  })
  ws.addEventListener('close', (e) => resolve(e.code))
})
if (code !== 1000 || finals.length === 0) fail(`stream closed ${code} with ${finals.length} finals`)
console.log('stream finals', finals.length)

await Bun.sleep(300)
const usage = (await (await fetch(`${base}/api/me/usage`, { headers: auth })).json()) as { sessions: object[] }
if (usage.sessions.length !== before + 1) fail(`usage rows ${before} → ${usage.sessions.length}`)
console.log('usage row', JSON.stringify(usage.sessions[0]))
console.log('smoke: OK')
