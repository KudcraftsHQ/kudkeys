import { useState } from 'react'
import { signIn, signUp } from '@/lib/auth-client'
import { ErrorNote, Wordmark } from '@/components/Shell'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

type Mode = 'sign-in' | 'sign-up'

export function Login() {
  const [mode, setMode] = useState<Mode>('sign-in')
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (submitting) return
    setSubmitting(true)
    setError(null)
    // The session lands in useSession() and App switches to the signed-in routes.
    const { error: err } =
      mode === 'sign-in'
        ? await signIn.email({ email, password })
        : await signUp.email({ email, password, name: name.trim() || email.split('@')[0]! })
    if (err) setError(humanize(err.code, err.message ?? err.statusText))
    setSubmitting(false)
  }

  const switchMode = () => {
    setMode(mode === 'sign-in' ? 'sign-up' : 'sign-in')
    setError(null)
  }

  return (
    <div className="min-h-svh flex flex-col px-5 py-10">
      <Wordmark />
      <div className="m-auto w-full max-w-sm">
        <h1 className="text-[28px] leading-tight">{mode === 'sign-in' ? 'Sign in' : 'Create your account'}</h1>
        <p className="mt-1.5 text-[13.5px] text-ink-soft">
          {mode === 'sign-in'
            ? 'Your voice keywords, usage and signed-in devices.'
            : 'Only invited emails can create an account.'}
        </p>
        <form onSubmit={handleSubmit} className="mt-8 flex flex-col gap-4">
          {mode === 'sign-up' && (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="name">Name</Label>
              <Input id="name" autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
          )}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="email">Email</Label>
            <Input id="email" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="password">Password</Label>
            <Input
              id="password"
              type="password"
              autoComplete={mode === 'sign-in' ? 'current-password' : 'new-password'}
              minLength={mode === 'sign-up' ? 8 : undefined}
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
            {mode === 'sign-up' && <span className="text-[12px] text-muted">At least 8 characters.</span>}
          </div>
          {error && <ErrorNote>{error}</ErrorNote>}
          <Button type="submit" size="lg" className="w-full mt-1" disabled={submitting}>
            {submitting ? 'One moment…' : mode === 'sign-in' ? 'Sign in' : 'Create account'}
          </Button>
        </form>
        <p className="mt-6 text-[13px] text-ink-soft">
          {mode === 'sign-in' ? 'First time here? ' : 'Already have an account? '}
          <button type="button" onClick={switchMode} className="text-blue underline-offset-4 hover:underline">
            {mode === 'sign-in' ? 'Create an account' : 'Sign in'}
          </button>
        </p>
      </div>
    </div>
  )
}

function humanize(code: string | undefined, message: string): string {
  if (code === 'EMAIL_NOT_ALLOWED') return 'This email isn’t on the Kud Keys invite list.'
  if (code === 'INVALID_EMAIL_OR_PASSWORD') return 'Wrong email or password.'
  if (code === 'USER_ALREADY_EXISTS_USE_ANOTHER_EMAIL') return 'There’s already an account for this email. Sign in instead.'
  if (code === 'PASSWORD_TOO_SHORT') return 'Use at least 8 characters.'
  return message
}
