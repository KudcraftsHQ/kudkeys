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
  { to: '/words', label: 'Words' },
  { to: '/backups', label: 'Backups' },
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
        {/* Phones: brand and sign-out on the first row, the tabs on their own scrollable row. */}
        <div className="mx-auto max-w-2xl px-5 flex flex-wrap sm:flex-nowrap items-center gap-x-6">
          <Wordmark className="h-14 shrink-0 whitespace-nowrap" />
          <nav className="order-last sm:order-none w-full sm:w-auto min-w-0 -mx-2.5 sm:mx-0 pb-2 sm:pb-0 flex items-center gap-1 overflow-x-auto">
            {NAV.map((n) => (
              <NavLink
                key={n.to}
                to={n.to}
                end
                className={({ isActive }) =>
                  cn(
                    'px-2.5 h-8 shrink-0 inline-flex items-center rounded-[8px] text-[13.5px] font-medium',
                    isActive ? 'bg-cream-deep text-ink' : 'text-ink-soft hover:text-ink',
                  )
                }
              >
                {n.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center">
            <Button variant="ghost" size="icon" onClick={handleSignOut} aria-label={`Sign out ${email}`} title={`Sign out ${email}`}>
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
