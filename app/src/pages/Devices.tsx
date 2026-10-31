import { useMutation, useQuery } from '@tanstack/react-query'
import { toast } from 'sonner'
import { authClient } from '@/lib/auth-client'
import { queryClient, SESSIONS_KEY } from '@/lib/api'
import { describeDevice, formatRelative } from '@/lib/utils'
import { ErrorNote, PageHeader } from '@/components/Shell'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

async function listSessions() {
  const { data, error } = await authClient.listSessions()
  if (error) throw new Error(error.message ?? 'Could not load sessions')
  return [...data].sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
}

export function Devices({ currentToken }: { currentToken: string }) {
  const { data, error } = useQuery({ queryKey: SESSIONS_KEY, queryFn: listSessions })
  const revoke = useMutation({
    mutationFn: async (token: string) => {
      const { error: err } = await authClient.revokeSession({ token })
      if (err) throw new Error(err.message ?? 'Could not sign that device out')
    },
    onSuccess: () => {
      toast('Signed out on that device.')
      void queryClient.invalidateQueries({ queryKey: SESSIONS_KEY })
    },
    onError: (e) => toast(e.message),
  })

  return (
    <>
      <PageHeader title="Devices" sub="Everywhere you're signed in: this browser, your phone's keyboard, anything else." />
      {error && <ErrorNote>{error.message}</ErrorNote>}
      {!data && !error && <Skeleton className="h-40 w-full" />}
      {data && (
        <section className="panel divide-y divide-line">
          {data.map((s) => {
            const current = s.token === currentToken
            return (
              <div key={s.id} className="px-5 py-3.5 flex items-center gap-4">
                <div className="min-w-0 flex-1">
                  <div className="text-[14px] font-medium flex items-center gap-2">
                    {describeDevice(s.userAgent)}
                    {current && <span className="chip !h-5 !px-2 !text-[11.5px] !bg-mint !text-olive">This browser</span>}
                  </div>
                  <div className="mt-0.5 text-[12.5px] text-muted truncate">
                    Active {formatRelative(s.updatedAt)} · signed in {formatRelative(s.createdAt)}
                    {s.ipAddress ? ` · ${s.ipAddress}` : ''}
                  </div>
                </div>
                {!current && (
                  <Button variant="outline" size="sm" disabled={revoke.isPending} onClick={() => revoke.mutate(s.token)}>
                    Sign out
                  </Button>
                )}
              </div>
            )
          })}
        </section>
      )}
    </>
  )
}
