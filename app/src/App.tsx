import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { useSession } from '@/lib/auth-client'
import { Shell } from '@/components/Shell'
import { Skeleton } from '@/components/ui/skeleton'
import { Login } from '@/pages/Login'
import { Profile } from '@/pages/Profile'
import { UsagePage } from '@/pages/Usage'
import { Devices } from '@/pages/Devices'
import { WordsPage } from '@/pages/Words'
import { BackupsPage } from '@/pages/Backups'
import { useMountEffect } from '@/hooks/useMountEffect'

export function App() {
  const { data, isPending } = useSession()

  if (isPending) {
    return (
      <div className="mx-auto max-w-2xl px-5 py-16 flex flex-col gap-3">
        <Skeleton className="h-6 w-40" />
        <Skeleton className="h-40 w-full" />
      </div>
    )
  }

  if (!data?.user) {
    if (__DEV_AUTO_SIGNIN__) return <DevAutoSignIn />
    return (
      <BrowserRouter key="public">
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </BrowserRouter>
    )
  }

  return (
    <BrowserRouter key="signed-in">
      <Routes>
        <Route element={<Shell email={data.user.email} />}>
          <Route path="/" element={<Profile />} />
          <Route path="/usage" element={<UsagePage />} />
          <Route path="/words" element={<WordsPage />} />
          <Route path="/backups" element={<BackupsPage />} />
          <Route path="/devices" element={<Devices currentToken={data.session.token} />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}

/** Dev worktrees only: hand the browser to the server's auto sign-in (server/dev-auto-sign-in.ts). */
function DevAutoSignIn() {
  useMountEffect(() => {
    const next = window.location.pathname + window.location.search
    window.location.replace(`/api/auth/dev/auto-sign-in?next=${encodeURIComponent(next)}`)
  })
  return <div className="mx-auto max-w-2xl px-5 py-16 text-sm">Signing in…</div>
}
