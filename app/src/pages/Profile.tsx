import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { api, ME_KEY, queryClient, type Me } from '@/lib/api'
import { cn } from '@/lib/utils'
import { ErrorNote, PageHeader } from '@/components/Shell'
import { KeywordChips } from '@/components/KeywordChips'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'

const LANGUAGES = [
  { code: 'id', label: 'Indonesian' },
  { code: 'en', label: 'English' },
  { code: 'jv', label: 'Javanese' },
  { code: 'su', label: 'Sundanese' },
  { code: 'ms', label: 'Malay' },
  { code: 'ar', label: 'Arabic' },
]

export function Profile() {
  const { data, error } = useQuery({ queryKey: ME_KEY, queryFn: api.me })
  return (
    <>
      <PageHeader title="Profile" sub="What the keyboard knows about you when you talk to it." />
      {error && <ErrorNote>{error.message}</ErrorNote>}
      {!data && !error && <Skeleton className="h-80 w-full" />}
      {/* key: a saved profile resets the form to what the server stored */}
      {data && <ProfileForm key={JSON.stringify(data)} me={data} />}
    </>
  )
}

function ProfileForm({ me }: { me: Me }) {
  const [name, setName] = useState(me.name)
  const [keywords, setKeywords] = useState(me.voiceKeywords)
  const [languages, setLanguages] = useState(me.languages)

  const save = useMutation({
    mutationFn: () => api.updateMe({ name: name.trim(), voiceKeywords: keywords, languages }),
    onSuccess: (saved) => {
      queryClient.setQueryData(ME_KEY, saved)
      toast('Saved. The keyboard uses it from the next voice session.')
    },
  })

  const dirty =
    name.trim() !== me.name ||
    JSON.stringify(keywords) !== JSON.stringify(me.voiceKeywords) ||
    JSON.stringify(languages) !== JSON.stringify(me.languages)

  const toggleLanguage = (code: string) => {
    if (languages.includes(code)) {
      if (languages.length > 1) setLanguages(languages.filter((l) => l !== code))
    } else setLanguages([...languages, code])
  }

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    save.mutate()
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4">
      <section className="panel p-5 flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="name">Name</Label>
          <Input id="name" value={name} maxLength={100} required onChange={(e) => setName(e.target.value)} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label>Email</Label>
          <div className="text-[14px] text-ink-soft">{me.email}</div>
        </div>
      </section>

      <section className="panel p-5 flex flex-col gap-3">
        <div>
          <h2 className="text-[15px]">Voice keywords</h2>
          <p className="mt-0.5 text-[13px] text-ink-soft">
            Names and brands Whisper should spell right. Sent with every voice session, never stored with what you said.
          </p>
        </div>
        <KeywordChips value={keywords} onChange={setKeywords} />
      </section>

      <section className="panel p-5 flex flex-col gap-3">
        <div>
          <h2 className="text-[15px]">Languages</h2>
          <p className="mt-0.5 text-[13px] text-ink-soft">
            With one language Whisper is told which; with several it detects per phrase.
          </p>
        </div>
        <div className="flex flex-wrap gap-1.5">
          {LANGUAGES.map((l) => {
            const on = languages.includes(l.code)
            return (
              <button
                key={l.code}
                type="button"
                aria-pressed={on}
                onClick={() => toggleLanguage(l.code)}
                className={cn(
                  'h-8 px-3 rounded-full text-[13px] font-medium border transition-colors',
                  on ? 'bg-ink text-paper border-ink' : 'bg-paper text-ink-soft border-line-strong hover:text-ink',
                )}
              >
                {l.label}
              </button>
            )
          })}
        </div>
      </section>

      {save.error && <ErrorNote>{save.error.message}</ErrorNote>}
      <div className="flex justify-end">
        <Button type="submit" size="lg" disabled={!dirty || save.isPending}>
          {save.isPending ? 'Saving…' : 'Save'}
        </Button>
      </div>
    </form>
  )
}
