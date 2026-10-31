// Usage periods are calendar weeks (Monday) and months in Jakarta time
// (UTC+7, no daylight saving), the zone the keyboard is used in.

const WIB_MS = 7 * 3600_000
const DAY_MS = 86_400_000

export function periodStarts(now: Date): { week: Date; month: Date } {
  // Shift so the UTC fields read Jakarta's wall clock.
  const local = new Date(now.getTime() + WIB_MS)
  const y = local.getUTCFullYear()
  const m = local.getUTCMonth()
  const dayStart = Date.UTC(y, m, local.getUTCDate()) - WIB_MS
  const sinceMonday = (local.getUTCDay() + 6) % 7
  return { week: new Date(dayStart - sinceMonday * DAY_MS), month: new Date(Date.UTC(y, m, 1) - WIB_MS) }
}

export function minutes(seconds: number): number {
  return Math.round((seconds / 60) * 10) / 10
}
