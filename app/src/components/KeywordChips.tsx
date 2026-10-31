import { useState } from 'react'
import { X } from 'lucide-react'
import { Input } from '@/components/ui/input'

export const MAX_KEYWORDS = 100
export const MAX_KEYWORD_LENGTH = 100

/** Editable chips. Enter or comma adds; pasting a comma-separated list adds them all. */
export function KeywordChips({ value, onChange }: { value: string[]; onChange: (next: string[]) => void }) {
  const [draft, setDraft] = useState('')
  const [hint, setHint] = useState<string | null>(null)

  const add = (raw: string) => {
    const seen = new Set(value.map((k) => k.toLowerCase()))
    const next = [...value]
    for (const part of raw.split(',')) {
      const k = part.replace(/[<>\r\n]/g, ' ').trim()
      if (!k || seen.has(k.toLowerCase())) continue
      if (k.length > MAX_KEYWORD_LENGTH) {
        setHint(`Keywords are at most ${MAX_KEYWORD_LENGTH} characters.`)
        continue
      }
      if (next.length >= MAX_KEYWORDS) {
        setHint(`At most ${MAX_KEYWORDS} keywords.`)
        break
      }
      seen.add(k.toLowerCase())
      next.push(k)
    }
    onChange(next)
    setDraft('')
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      if (draft.trim()) add(draft)
    } else if (e.key === 'Backspace' && !draft && value.length) {
      onChange(value.slice(0, -1))
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {value.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {value.map((k) => (
            <span key={k} className="chip">
              {k}
              <button
                type="button"
                onClick={() => onChange(value.filter((x) => x !== k))}
                className="grid place-items-center size-5 rounded-full text-muted hover:text-ink hover:bg-line"
                aria-label={`Remove ${k}`}
              >
                <X className="size-3.5" />
              </button>
            </span>
          ))}
        </div>
      )}
      <Input
        value={draft}
        placeholder={value.length ? 'Add another…' : 'Fenno, TypeMap, Kudcrafts…'}
        onChange={(e) => {
          setDraft(e.target.value)
          setHint(null)
        }}
        onKeyDown={handleKeyDown}
        onBlur={() => draft.trim() && add(draft)}
        onPaste={(e) => {
          const text = e.clipboardData.getData('text')
          if (text.includes(',')) {
            e.preventDefault()
            add(text)
          }
        }}
      />
      <div className="flex justify-between text-[12px] text-muted">
        <span>{hint ?? 'Press Enter or comma to add.'}</span>
        <span className="tabular">
          {value.length}/{MAX_KEYWORDS}
        </span>
      </div>
    </div>
  )
}
