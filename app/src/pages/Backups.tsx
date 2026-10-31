import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Download, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { api, BACKUPS_KEY, queryClient, SYNC_STATUS_KEY } from '@/lib/api'
import { formatBytes, formatRelative, formatWhen } from '@/lib/utils'
import { ErrorNote, PageHeader } from '@/components/Shell'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

export function BackupsPage() {
  const { data, error } = useQuery({ queryKey: BACKUPS_KEY, queryFn: api.backups })
  const [confirming, setConfirming] = useState<string | null>(null)
  const remove = useMutation({
    mutationFn: api.deleteBackup,
    onSuccess: () => {
      toast('Backup deleted.')
      setConfirming(null)
      void queryClient.invalidateQueries({ queryKey: BACKUPS_KEY })
      void queryClient.invalidateQueries({ queryKey: SYNC_STATUS_KEY })
    },
    onError: (e) => toast(e.message),
  })

  return (
    <>
      <PageHeader
        title="Backups"
        sub="Your phone backs up the keyboard once a day: learned words, your dictionaries and settings. The newest automatic backup of each of the last 14 days and 8 weeks is kept, plus your last 10 manual ones."
      />
      {error && <ErrorNote>{error.message}</ErrorNote>}
      {!data && !error && <Skeleton className="h-40 w-full" />}
      {data && data.length === 0 && (
        <section className="panel px-5 py-8 text-[13.5px] text-ink-soft">
          No backups yet. Turn on Automatic backup in the keyboard's Account screen, or tap Back up now.
        </section>
      )}
      {data && data.length > 0 && (
        <section className="panel divide-y divide-line">
          {data.map((b) => (
            <div key={b.id} className="px-5 py-3.5 flex items-center gap-3">
              <div className="min-w-0 flex-1">
                <div className="text-[14px] font-medium flex items-center gap-2">
                  {formatWhen(b.createdAt)}
                  {b.kind === 'manual' && <span className="chip !h-5 !px-2 !text-[11.5px]">Manual</span>}
                </div>
                <div className="mt-0.5 text-[12.5px] text-muted truncate">
                  {b.deviceName || 'Unknown device'} · {formatBytes(b.sizeBytes)} · {formatRelative(b.createdAt)}
                </div>
              </div>
              {confirming === b.id ? (
                <>
                  <Button variant="ghost" size="sm" onClick={() => setConfirming(null)}>
                    Keep
                  </Button>
                  <Button variant="rose" size="sm" disabled={remove.isPending} onClick={() => remove.mutate(b.id)}>
                    Delete
                  </Button>
                </>
              ) : (
                <>
                  <Button variant="outline" size="sm" asChild>
                    <a href={`/api/backups/${encodeURIComponent(b.id)}`} download>
                      <Download /> Download
                    </a>
                  </Button>
                  <Button variant="ghost" size="icon" onClick={() => setConfirming(b.id)} aria-label="Delete backup" title="Delete">
                    <Trash2 />
                  </Button>
                </>
              )}
            </div>
          ))}
        </section>
      )}
      <p className="mt-3 text-[12.5px] text-muted">To restore, open the keyboard's Account screen → Backups on the phone.</p>
    </>
  )
}
