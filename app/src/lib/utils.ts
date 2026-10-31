import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}

// Usage periods are Jakarta calendar weeks and months (server/lib/usage.ts),
// so times beside them are shown in Jakarta time too.
export const WIB_TZ = 'Asia/Jakarta'

export function formatWhen(iso: string | Date): string {
  const d = typeof iso === 'string' ? new Date(iso) : iso
  return d.toLocaleString('en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', timeZone: WIB_TZ })
}

export function formatRelative(iso: string | Date): string {
  const d = typeof iso === 'string' ? new Date(iso) : iso
  const min = Math.round((Date.now() - d.getTime()) / 60_000)
  if (min < 1) return 'just now'
  if (min < 60) return `${min} min ago`
  const hr = Math.round(min / 60)
  if (hr < 24) return `${hr} h ago`
  const day = Math.round(hr / 24)
  if (day < 30) return `${day} d ago`
  return formatWhen(d)
}

export function formatSeconds(s: number): string {
  if (s < 60) return `${s.toFixed(1)} s`
  return `${Math.floor(s / 60)} min ${Math.round(s % 60)} s`
}

/** "Kud Keys on Android", "Chrome on macOS", … from a User-Agent. */
export function describeDevice(ua: string | null | undefined): string {
  if (!ua) return 'Unknown device'
  const os = /Android/i.test(ua)
    ? 'Android'
    : /iPhone|iPad/i.test(ua)
      ? 'iOS'
      : /Mac OS X|Macintosh/i.test(ua)
        ? 'macOS'
        : /Windows/i.test(ua)
          ? 'Windows'
          : /Linux/i.test(ua)
            ? 'Linux'
            : null
  const app = /KudKeys/i.test(ua)
    ? 'Kud Keys keyboard'
    : /Edg\//.test(ua)
      ? 'Edge'
      : /Firefox\//.test(ua)
        ? 'Firefox'
        : /Chrome\//.test(ua)
          ? 'Chrome'
          : /Safari\//.test(ua)
            ? 'Safari'
            : /okhttp|Dalvik/i.test(ua)
              ? 'Android app'
              : /curl|bun|node/i.test(ua)
                ? 'Script'
                : 'Browser'
  return os ? `${app} on ${os}` : app
}

export function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(n < 10 * 1024 ? 1 : 0)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
