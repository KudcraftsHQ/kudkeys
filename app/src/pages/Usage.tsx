import { useQuery } from '@tanstack/react-query'
import { api, USAGE_KEY, type UsagePeriod } from '@/lib/api'
import { formatSeconds, formatWhen } from '@/lib/utils'
import { ErrorNote, PageHeader } from '@/components/Shell'
import { Skeleton } from '@/components/ui/skeleton'

export function UsagePage() {
  const { data, error } = useQuery({ queryKey: USAGE_KEY, queryFn: api.usage })
  return (
    <>
      <PageHeader title="Usage" sub="Audio sent from the keyboard. Only the numbers are kept — never what you said." />
      {error && <ErrorNote>{error.message}</ErrorNote>}
      {!data && !error && <Skeleton className="h-64 w-full" />}
      {data && (
        <div className="flex flex-col gap-4">
          <div className="grid grid-cols-2 gap-3">
            <Stat label="This week" period={data.week} />
            <Stat label="This month" period={data.month} />
          </div>
          <section className="panel overflow-hidden">
            <div className="px-5 py-3 border-b border-line eyebrow">Recent sessions</div>
            {data.sessions.length === 0 ? (
              <p className="px-5 py-8 text-[13.5px] text-ink-soft">No voice sessions yet. Hold the mic key and talk.</p>
            ) : (
              <table className="w-full text-[13.5px]">
                <thead>
                  <tr className="text-left text-muted text-[12px]">
                    <th className="font-medium px-5 py-2">When (WIB)</th>
                    <th className="font-medium px-2 py-2 text-right">Audio</th>
                    <th className="font-medium px-2 py-2 text-right">Phrases</th>
                    <th className="font-medium px-5 py-2 text-right">Ended</th>
                  </tr>
                </thead>
                <tbody>
                  {data.sessions.map((s) => (
                    <tr key={s.id} className="border-t border-line">
                      <td className="px-5 py-2.5">{formatWhen(s.startedAt)}</td>
                      <td className="px-2 py-2.5 text-right tabular">{formatSeconds(s.audioSeconds)}</td>
                      <td className="px-2 py-2.5 text-right tabular">{s.finals}</td>
                      <td className="px-5 py-2.5 text-right text-ink-soft">{s.outcome}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </div>
      )}
    </>
  )
}

function Stat({ label, period }: { label: string; period: UsagePeriod }) {
  return (
    <div className="panel p-5">
      <div className="eyebrow">{label}</div>
      <div className="mt-2 text-[28px] font-semibold tracking-[-0.02em] tabular">
        {period.audioMinutes.toLocaleString('en-US')}
        <span className="ml-1 text-[14px] font-medium text-ink-soft">min</span>
      </div>
      <div className="mt-0.5 text-[12.5px] text-muted">
        {period.sessions} session{period.sessions === 1 ? '' : 's'}
      </div>
    </div>
  )
}
