// Smoke test of a running server (e.g. the Docker image): sign up an allowed
// email, read /api/me, call /api/text/fix, sync settings and (made-up) words,
// upload and download a backup, stream two seconds of tone with the session
// token and see the usage row. Never point it at production: it creates an account.
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

res = await fetch(`${base}/api/text/fix`, { method: 'POST', headers: { ...json, ...auth }, body: JSON.stringify({ text: 'smoke test' }) })
if (res.status !== 200) fail(`/api/text/fix ${res.status} ${await res.text()}`)
console.log('text fix', JSON.stringify(await res.json()))

// Sync: settings, words, import, and a backup round trip (raw SQL through Prisma and pgbouncer, bytea included).
const syncAt = new Date().toISOString()
res = await fetch(`${base}/api/sync/settings`, {
  method: 'PUT',
  headers: { ...json, ...auth },
  body: JSON.stringify({ prefs: { smoke_theme: 'dark', smoke_vibrate: true }, updatedAt: syncAt, deviceId: 'smoke' }),
})
if (res.status !== 200) fail(`PUT /api/sync/settings ${res.status} ${await res.text()}`)
const settings = (await (await fetch(`${base}/api/sync/settings`, { headers: auth })).json()) as { prefs: Record<string, unknown> }
if (settings.prefs.smoke_theme !== 'dark') fail(`GET /api/sync/settings ${JSON.stringify(settings)}`)
res = await fetch(`${base}/api/sync/words`, {
  method: 'POST',
  headers: { ...json, ...auth },
  body: JSON.stringify({ words: [{ word: 'smokezorb', locale: 'en', count: 2, source: 'typed', deleted: false, updatedAt: syncAt }] }),
})
const pushed = (await res.json()) as { accepted: number; now: string }
if (pushed.accepted !== 1) fail(`POST /api/sync/words ${JSON.stringify(pushed)}`)
res = await fetch(`${base}/api/sync/words/import`, {
  method: 'POST',
  headers: { ...json, ...auth },
  body: JSON.stringify({ locale: 'en', source: 'swiftkey', words: ['smokezorb', 'smokeflib'] }),
})
const imported = (await res.json()) as { added: number; skipped: number }
if (imported.added + imported.skipped !== 2) fail(`import ${JSON.stringify(imported)}`)
const pulled = (await (await fetch(`${base}/api/sync/words?since=${encodeURIComponent(pushed.now)}`, { headers: auth })).json()) as {
  words: { word: string; count: number }[]
}
if (pulled.words.find((w) => w.word === 'smokezorb')?.count !== 2) fail(`GET /api/sync/words ${JSON.stringify(pulled).slice(0, 300)}`)
const zip = new Uint8Array(4096).map((_, i) => (i < 4 ? [0x50, 0x4b, 0x03, 0x04][i]! : (i * 31) & 0xff))
res = await fetch(`${base}/api/backups`, {
  method: 'POST',
  headers: { ...auth, 'content-type': 'application/zip', 'x-device-id': 'smoke', 'x-device-name': 'Smoke', 'x-backup-kind': 'manual' },
  body: zip,
})
const backup = (await res.json()) as { id: string }
if (!backup.id) fail(`POST /api/backups ${res.status} ${JSON.stringify(backup)}`)
const back = new Uint8Array(await (await fetch(`${base}/api/backups/${backup.id}`, { headers: auth })).arrayBuffer())
if (back.length !== zip.length || back.some((b, i) => b !== zip[i])) fail(`backup round trip: ${back.length} bytes back`)
res = await fetch(`${base}/api/backups/${backup.id}`, { method: 'DELETE', headers: auth })
if (res.status !== 200) fail(`DELETE /api/backups ${res.status}`)
console.log('sync', JSON.stringify({ settings: Object.keys(settings.prefs).length, words: pulled.words.length, imported, backupBytes: back.length }))

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
