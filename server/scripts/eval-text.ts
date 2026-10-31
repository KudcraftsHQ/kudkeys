// Runs the text fixtures (server/test/fixtures/text) against real Groq models
// and writes a markdown comparison: each output, latency, and the fixture's
// simple checks. Spends a few cents of Groq credit; never run in CI.
//
//   GROQ_API_KEY=… bun server/scripts/eval-text.ts out.md [model …] [--runs 2] [--sleep 15000]

import { readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { createTextEngine, type TextEngine } from '../text/engine'
import { GROQ_CHAT_URL } from '../text/groq-chat'

type Fixture = {
  kind: 'tidy' | 'fix' | 'instruct'
  about: string
  input: any
  keywords: string[]
  expect?: { contains?: string[]; notContains?: string[]; changed?: boolean }
}

const args = Bun.argv.slice(2)
const runsIdx = args.indexOf('--runs')
const runs = runsIdx >= 0 ? Number(args.splice(runsIdx, 2)[1]) : 2
const sleepAt = args.indexOf('--sleep')
const sleepArg = sleepAt >= 0 ? args.splice(sleepAt, 2)[1] : undefined
const [outPath, ...models] = args
if (!outPath) throw new Error('usage: eval-text.ts out.md [model …]')
const apiKey = process.env.GROQ_API_KEY
if (!apiKey) throw new Error('GROQ_API_KEY is not set')
const list = models.length ? models : ['openai/gpt-oss-120b', 'openai/gpt-oss-20b', 'qwen/qwen3.8-27b']

const dir = join(import.meta.dir, '../test/fixtures/text')
const fixtures = readdirSync(dir)
  .filter((f) => f.endsWith('.json'))
  .sort()
  .map((f) => ({ name: f.replace(/\.json$/, ''), ...(JSON.parse(readFileSync(join(dir, f), 'utf8')) as Fixture) }))

async function once(engine: TextEngine, f: Fixture) {
  const ctx = { keywords: f.keywords, languages: ['id', 'en'] }
  const t0 = performance.now()
  try {
    const out =
      f.kind === 'tidy' ? await engine.tidy(f.input, ctx) : f.kind === 'fix' ? await engine.fix(f.input, ctx) : await engine.instruct(f.input, ctx)
    return { ms: Math.round(performance.now() - t0), out: out as any, error: null as string | null }
  } catch (e) {
    return { ms: Math.round(performance.now() - t0), out: null, error: (e as Error).message }
  }
}

function check(f: Fixture, out: any): string[] {
  if (!out) return ['error']
  const fails: string[] = []
  for (const s of f.expect?.contains ?? []) if (!out.text.toLowerCase().includes(s.toLowerCase())) fails.push(`missing "${s}"`)
  for (const s of f.expect?.notContains ?? []) if (out.text.toLowerCase().includes(s.toLowerCase())) fails.push(`has "${s}"`)
  if (f.expect?.changed !== undefined && out.changed !== f.expect.changed) fails.push(`changed=${out.changed}`)
  return fails
}

const sleepMs = sleepArg ? Number(sleepArg) : 15_000
const rows: string[] = []
const detail: string[] = []
const summary: Record<string, { ms: number[]; fails: number; errors: number }> = {}
// Groq's limits are per model (8k tokens/min on the free tier), so models run side by side and each paces itself.
const perModel = await Promise.all(
  list.map(async (model) => {
    const engine = createTextEngine({ apiKey, url: GROQ_CHAT_URL, model }, // one model for every kind
      { timeoutMs: { fix: 30_000, tidy: 60_000, instruct: 60_000 } })
    const s = { ms: [] as number[], fails: 0, errors: 0 }
    const r1: string[] = []
    const d1: string[] = []
    for (const f of fixtures) {
      for (let r = 1; r <= runs; r++) {
        let res = await once(engine, f)
        for (let tries = 0; res.error === 'model_rate_limited' && tries < 4; tries++) {
          await Bun.sleep(20_000)
          res = await once(engine, f)
        }
        await Bun.sleep(sleepMs)
        const fails = check(f, res.out)
        s.ms.push(res.ms)
        if (res.error) s.errors++
        else if (fails.length) s.fails++
        r1.push(`| ${model} | ${f.name} | ${r} | ${res.ms} | ${res.error ? `error: ${res.error}` : fails.length ? fails.join('; ') : 'ok'} |`)
        d1.push(
          `### ${model} · ${f.name} · run ${r} · ${res.ms} ms\n\n${res.error ? `**error:** ${res.error}` : '```json\n' + JSON.stringify(res.out, null, 2) + '\n```'}\n`,
        )
        console.log(`${model} ${f.name} #${r}: ${res.ms} ms ${res.error ?? (fails.join('; ') || 'ok')}`)
      }
    }
    return { model, s, r1, d1 }
  }),
)
for (const m of perModel) {
  summary[m.model] = m.s
  rows.push(...m.r1)
  detail.push(...m.d1)
}

const median = (a: number[]) => [...a].sort((x, y) => x - y)[Math.floor(a.length / 2)]
const md = [
  `# Kud Keys text endpoints — model comparison`,
  ``,
  `${new Date().toISOString()} · ${fixtures.length} fixtures × ${runs} runs · prompts from server/text/prompts.ts`,
  ``,
  `| model | median ms | max ms | check failures | errors |`,
  `|---|---|---|---|---|`,
  ...Object.entries(summary).map(([m, s]) => `| ${m} | ${median(s.ms)} | ${Math.max(...s.ms)} | ${s.fails} | ${s.errors} |`),
  ``,
  `## Fixtures`,
  ``,
  ...fixtures.map((f) => `- **${f.name}** (${f.kind}): ${f.about}`),
  ``,
  `## Every run`,
  ``,
  `| model | fixture | run | ms | checks |`,
  `|---|---|---|---|---|`,
  ...rows,
  ``,
  `## Outputs`,
  ``,
  ...detail,
].join('\n')
writeFileSync(outPath, md)
console.log(`wrote ${outPath}`)
