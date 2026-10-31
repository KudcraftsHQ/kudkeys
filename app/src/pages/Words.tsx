import { useDeferredValue, useState } from 'react'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { Plus, RotateCcw, Search, X } from 'lucide-react'
import { toast } from 'sonner'
import { api, queryClient, SYNC_STATUS_KEY, WORDS_KEY, type SyncStatus, type Word, type WordSource } from '@/lib/api'
import { cn, formatRelative } from '@/lib/utils'
import { ErrorNote, PageHeader } from '@/components/Shell'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'

const PAGE = 50
const SOURCES: { id: WordSource; label: string }[] = [
  { id: 'typed', label: 'Typed' },
  { id: 'swiftkey', label: 'SwiftKey' },
  { id: 'manual', label: 'Added' },
]
const sourceLabel = (s: string) => SOURCES.find((x) => x.id === s)?.label ?? s

const refresh = () => {
  void queryClient.invalidateQueries({ queryKey: WORDS_KEY })
  void queryClient.invalidateQueries({ queryKey: SYNC_STATUS_KEY })
}

export function WordsPage() {
  const status = useQuery({ queryKey: SYNC_STATUS_KEY, queryFn: api.syncStatus })
  return (
    <>
      <PageHeader title="Words" sub="The words your keyboard knows you use, synced to every phone you sign in on." />
      {status.error && <ErrorNote>{status.error.message}</ErrorNote>}
      {!status.data && !status.error && <Skeleton className="h-28 w-full" />}
      {status.data && (
        <div className="flex flex-col gap-4">
          <Totals status={status.data} />
          <AddWord locales={Object.keys(status.data.words.byLocale)} />
          <WordList locales={Object.keys(status.data.words.byLocale).sort()} />
          <SettingsLine settings={status.data.settings} />
        </div>
      )}
    </>
  )
}

function Totals({ status }: { status: SyncStatus }) {
  const w = status.words
  const locales = Object.entries(w.byLocale).sort((a, b) => b[1] - a[1])
  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
      <Tile label="Words" value={w.total} sub={w.lastChangedAt ? `changed ${formatRelative(w.lastChangedAt)}` : 'none yet'} />
      {SOURCES.map((s) => (
        <Tile key={s.id} label={s.label} value={w.bySource[s.id] ?? 0} />
      ))}
      {locales.length > 0 && (
        <div className="col-span-2 sm:col-span-4 flex flex-wrap gap-1.5 text-[12.5px] text-ink-soft">
          {locales.map(([l, n]) => (
            <span key={l} className="chip">
              {l} <span className="tabular text-muted">{n.toLocaleString('en-US')}</span>
            </span>
          ))}
          {w.removed > 0 && <span className="chip !text-muted">removed {w.removed.toLocaleString('en-US')}</span>}
        </div>
      )}
    </div>
  )
}

function Tile({ label, value, sub }: { label: string; value: number; sub?: string }) {
  return (
    <div className="panel p-4">
      <div className="eyebrow">{label}</div>
      <div className="mt-1.5 text-[24px] font-semibold tracking-[-0.02em] tabular">{value.toLocaleString('en-US')}</div>
      {sub && <div className="mt-0.5 text-[12px] text-muted">{sub}</div>}
    </div>
  )
}

function AddWord({ locales }: { locales: string[] }) {
  const [word, setWord] = useState('')
  const [locale, setLocale] = useState(locales[0] ?? 'en')
  const add = useMutation({
    mutationFn: () => api.putWord({ word: word.trim(), locale: locale.trim(), count: 1, source: 'manual', deleted: false }),
    onSuccess: () => {
      toast(`Added. Your phone picks it up at its next sync.`)
      setWord('')
      refresh()
    },
  })
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (word.trim()) add.mutate()
  }
  return (
    <form onSubmit={handleSubmit} className="panel p-4 flex flex-col gap-2">
      <div className="flex gap-2">
        <Input value={word} onChange={(e) => setWord(e.target.value)} maxLength={48} placeholder="Add a word" aria-label="Word" />
        <Input
          value={locale}
          onChange={(e) => setLocale(e.target.value)}
          list="word-locales"
          className="w-24"
          aria-label="Language"
          placeholder="en"
        />
        <datalist id="word-locales">
          {locales.map((l) => (
            <option key={l} value={l} />
          ))}
        </datalist>
        <Button type="submit" variant="outline" disabled={!word.trim() || add.isPending}>
          <Plus /> Add
        </Button>
      </div>
      {add.error && <ErrorNote>{add.error.message}</ErrorNote>}
    </form>
  )
}

function WordList({ locales }: { locales: string[] }) {
  const [q, setQ] = useState('')
  const [locale, setLocale] = useState<string | undefined>()
  const [source, setSource] = useState<WordSource | undefined>()
  const [removed, setRemoved] = useState(false)
  const [offset, setOffset] = useState(0)
  const search = useDeferredValue(q.trim())
  const filters = { q: search || undefined, locale, source, removed, limit: PAGE }
  const list = useQuery({
    queryKey: [...WORDS_KEY, filters, offset],
    queryFn: () => api.words({ ...filters, offset }),
    placeholderData: keepPreviousData,
  })
  const setFilter = (f: () => void) => {
    f()
    setOffset(0)
  }

  const change = useMutation({
    mutationFn: (w: Word) => api.putWord({ ...w, deleted: !w.deleted }),
    onSuccess: (_, w) => {
      toast(w.deleted ? `“${w.word}” is back.` : `Removed “${w.word}”. Your phone stops suggesting it after its next sync.`)
      refresh()
    },
    onError: (e) => toast(e.message),
  })

  const data = list.data
  return (
    <section className="panel overflow-hidden">
      <div className="px-4 py-3 border-b border-line flex flex-col gap-2.5">
        <div className="relative">
          <Search className="size-4 absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
          <Input value={q} onChange={(e) => setFilter(() => setQ(e.target.value))} placeholder="Search words" className="pl-9" aria-label="Search words" />
        </div>
        <div className="flex flex-wrap gap-1.5">
          <Pill on={!removed} onClick={() => setFilter(() => setRemoved(false))}>
            In use
          </Pill>
          <Pill on={removed} onClick={() => setFilter(() => setRemoved(true))}>
            Removed
          </Pill>
          <span className="w-px bg-line mx-1" />
          {SOURCES.map((s) => (
            <Pill key={s.id} on={source === s.id} onClick={() => setFilter(() => setSource(source === s.id ? undefined : s.id))}>
              {s.label}
            </Pill>
          ))}
          {locales.length > 1 && <span className="w-px bg-line mx-1" />}
          {locales.length > 1 &&
            locales.map((l) => (
              <Pill key={l} on={locale === l} onClick={() => setFilter(() => setLocale(locale === l ? undefined : l))}>
                {l}
              </Pill>
            ))}
        </div>
      </div>
      {list.error && (
        <div className="p-4">
          <ErrorNote>{list.error.message}</ErrorNote>
        </div>
      )}
      {!data && !list.error && <Skeleton className="h-64 m-4" />}
      {data && data.words.length === 0 && (
        <p className="px-5 py-8 text-[13.5px] text-ink-soft">
          {search || source || locale ? 'No words match.' : removed ? 'Nothing removed.' : 'No words yet. They arrive with the first sync from your phone.'}
        </p>
      )}
      {data && data.words.length > 0 && (
        <table className={cn('w-full text-[13.5px]', list.isPlaceholderData && 'opacity-60')}>
          <thead>
            <tr className="text-left text-muted text-[12px]">
              <th className="font-medium px-5 py-2">Word</th>
              <th className="font-medium px-2 py-2 hidden sm:table-cell">Source</th>
              <th className="font-medium px-2 py-2 text-right">Used</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {data.words.map((w) => (
              <tr key={`${w.locale}/${w.word}`} className="border-t border-line">
                <td className="px-5 py-2">
                  <span className={cn('font-medium', w.deleted && 'text-muted line-through')}>{w.word}</span>
                  <span className="ml-2 text-[12px] text-muted">{w.locale}</span>
                </td>
                <td className="px-2 py-2 text-ink-soft hidden sm:table-cell">{sourceLabel(w.source)}</td>
                <td className="px-2 py-2 text-right tabular text-ink-soft">{w.count.toLocaleString('en-US')}</td>
                <td className="px-3 py-1.5 text-right">
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={change.isPending}
                    onClick={() => change.mutate(w)}
                    aria-label={w.deleted ? `Restore ${w.word}` : `Remove ${w.word}`}
                    title={w.deleted ? 'Restore' : 'Remove'}
                  >
                    {w.deleted ? <RotateCcw /> : <X />}
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data && data.total > PAGE && (
        <div className="px-5 py-3 border-t border-line flex items-center gap-3 text-[12.5px] text-ink-soft">
          <span className="tabular">
            {(offset + 1).toLocaleString('en-US')}–{Math.min(offset + PAGE, data.total).toLocaleString('en-US')} of{' '}
            {data.total.toLocaleString('en-US')}
          </span>
          <div className="ml-auto flex gap-2">
            <Button variant="outline" size="sm" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE))}>
              Previous
            </Button>
            <Button variant="outline" size="sm" disabled={offset + PAGE >= data.total} onClick={() => setOffset(offset + PAGE)}>
              Next
            </Button>
          </div>
        </div>
      )}
    </section>
  )
}

function Pill({ on, onClick, children }: { on: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      onClick={onClick}
      className={cn(
        'h-7 px-2.5 rounded-full text-[12.5px] font-medium border transition-colors',
        on ? 'bg-ink text-paper border-ink' : 'bg-paper text-ink-soft border-line-strong hover:text-ink',
      )}
    >
      {children}
    </button>
  )
}

function SettingsLine({ settings }: { settings: SyncStatus['settings'] }) {
  return (
    <p className="text-[12.5px] text-muted">
      {settings
        ? `Keyboard settings (${settings.keys} keys) last synced ${formatRelative(settings.syncedAt)}. Change them on the phone.`
        : 'Keyboard settings have not been synced yet. Sign in on the phone and they follow.'}
    </p>
  )
}
