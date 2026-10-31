import { NavLink, Outlet } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { signOut } from '@/lib/auth-client'
import { queryClient } from '@/lib/api'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'

export function Wordmark({ className }: { className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-2 font-semibold text-[15px] tracking-[-0.01em]', className)}>
      <img src="/favicon.svg" alt="" className="size-6" />
      Kud Keys
    </span>
  )
}

const NAV = [
  { to: '/', label: 'Profile' },
  { to: '/usage', label: 'Usage' },
  { to: '/devices', label: 'Devices' },
]

export function Shell({ email }: { email: string }) {
  const handleSignOut = async () => {
    await signOut()
    queryClient.clear()
  }
  return (
    <div className="min-h-svh">
      <header className="border-b border-line bg-paper/80 backdrop-blur sticky top-0 z-10">
        <div className="mx-auto max-w-2xl px-5 h-14 flex items-center gap-6">
          <Wordmark />
          <nav className="flex items-center gap-1">
            {NAV.map((n) => (
              <NavLink
                key={n.to}
                to={n.to}
                end
                className={({ isActive }) =>
                  cn(
                    'px-2.5 h-8 inline-flex items-center rounded-[8px] text-[13.5px] font-medium',
                    isActive ? 'bg-cream-deep text-ink' : 'text-ink-soft hover:text-ink',
                  )
                }
              >
                {n.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-2">
            <span className="hidden sm:block text-[12.5px] text-muted truncate max-w-[180px]">{email}</span>
            <Button variant="ghost" size="icon" onClick={handleSignOut} aria-label="Sign out" title="Sign out">
              <LogOut />
            </Button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-2xl px-5 py-8">
        <Outlet />
      </main>
    </div>
  )
}

export function PageHeader({ title, sub }: { title: string; sub?: string }) {
  return (
    <div className="mb-6">
      <h1 className="text-[24px]">{title}</h1>
      {sub && <p className="mt-1 text-[13.5px] text-ink-soft">{sub}</p>}
    </div>
  )
}

export function ErrorNote({ children }: { children: React.ReactNode }) {
  return (
    <div className="border-l-2 border-rose bg-pink px-3 py-2 text-[13px] text-rose rounded-r-[6px]">{children}</div>
  )
}
