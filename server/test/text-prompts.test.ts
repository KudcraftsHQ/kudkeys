// The text prompts and the eval fixtures (scripts/eval-text.ts runs the
// fixtures against real Groq models; this only checks they are well formed and
// that the instruct prompt carries the rules the fixtures test).

import { expect, test } from 'bun:test'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { systemPrompt } from '../text/prompts'

const dir = join(import.meta.dir, 'fixtures/text')

test('every fixture is well formed', () => {
  const files = readdirSync(dir).filter((f) => f.endsWith('.json'))
  expect(files).toContain('instruct-id-sopan.json')
  for (const f of files) {
    const fx = JSON.parse(readFileSync(join(dir, f), 'utf8'))
    expect(['tidy', 'fix', 'instruct']).toContain(fx.kind)
    expect(typeof fx.about).toBe('string')
    if (fx.kind === 'instruct') expect(typeof fx.input.instruction).toBe('string')
  }
})

test('instruct: tone changes apply to the whole text, targeted edits stay targeted, nothing is added', () => {
  const p = systemPrompt('instruct', { keywords: [], languages: ['id', 'en'] })
  expect(p).toContain('apply it to the whole text, fully')
  expect(p).toContain('change only what it names')
  expect(p).toContain('never add content')
  // the timid answer the fixture guards against is named as wrong
  expect(p).toContain('NOT "Woi, tolong kirim invoicenya ya"')
  expect(p).toContain('do not switch to saya/Anda')
})
