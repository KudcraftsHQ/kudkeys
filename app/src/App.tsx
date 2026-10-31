import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { useSession } from '@/lib/auth-client'
import { Shell } from '@/components/Shell'
import { Skeleton } from '@/components/ui/skeleton'
import { Login } from '@/pages/Login'
import { Profile } from '@/pages/Profile'
import { UsagePage } from '@/pages/Usage'
import { Devices } from '@/pages/Devices'

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
          <Route path="/devices" element={<Devices currentToken={data.session.token} />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
