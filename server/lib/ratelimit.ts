// A sliding-window limit per key (user id), in memory. One process serves
// Kud Keys, so nothing needs to be shared.

export class RateLimiter {
  private hits = new Map<string, number[]>()

  constructor(
    private readonly max: number,
    private readonly windowMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** null when allowed (and counted), else the seconds until the next call may go. */
  take(key: string): number | null {
    const t = this.now()
    const recent = (this.hits.get(key) ?? []).filter((h) => h > t - this.windowMs)
    if (recent.length >= this.max) {
      this.hits.set(key, recent)
      return Math.max(1, Math.ceil((recent[0]! + this.windowMs - t) / 1000))
    }
    recent.push(t)
    this.hits.set(key, recent)
    return null
  }
}
