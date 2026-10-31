// Who may have a Kud Keys account. KUDKEYS_ALLOWED_EMAILS: comma-separated,
// case-insensitive. Empty = nobody can sign up or use an account. Checked at
// sign-up and again on every request, so removing an email (and restarting)
// locks that person out at once, without waiting for their sessions to expire.

export function parseAllowlist(raw: string | undefined): Set<string> {
  return new Set(
    (raw ?? '')
      .split(',')
      .map((s) => s.trim().toLowerCase())
      .filter(Boolean),
  )
}

export function isAllowed(list: Set<string>, email: string | null | undefined): boolean {
  if (!email) return false
  return list.has(email.toLowerCase())
}
