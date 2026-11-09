import { expect } from 'e2e'
import { describe, surfaceOf, test, type web } from '@e2e-dev/web'
import { APP_URL, as, ENGINE_KEY } from '../stack'

// The web app on a phone: Pixel 7 emulation (a mobile viewport with its device scale, touch, the Android user agent),
// real touch swipes, and what a phone user would see measured in the page: no sideways scrolling, no bottom bar pushed
// off the screen, no two pinned bars covering each other. Every screen is read-only: no clicks, no agent steps.
//
// web() has no touch or isMobile option, so each test takes the live page through surfaceOf(engine) and sets the
// device over CDP the way Playwright's devices['Pixel 7'] does (isMobile + hasTouch), then swipes with
// Input.dispatchTouchEvent: a real finger, not a wheel. Measurements are cutroom's (packages/e2e/tests/mobile.e2e.ts).
//
// Screens are the fixture stack's (server/scripts/e2e-stack.ts), as owner@keys.test, whose data no check changes.

/** Playwright 1.64's devices['Pixel 7'] */
const PIXEL_7 = {
  viewport: { width: 412, height: 839 },
  screen: { width: 412, height: 915 },
  deviceScaleFactor: 2.625,
  userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.8078.4 Mobile Safari/537.36',
}

// [id, path, heading the screen must show, signed in?]
const SCREENS: [id: string, path: string, heading: RegExp, signedIn: boolean][] = [
  ['sign in', '/login', /^Sign in$/, false],
  ['profile', '/', /^Profile$/, true],
  ['usage', '/usage', /^Usage$/, true],
  ['words', '/words', /^Words$/, true],
  ['backups', '/backups', /^Backups$/, true],
  ['devices', '/devices', /^Devices$/, true],
]
type Bar = { sel: string; top: number; bottom: number; onScreen: boolean }
type Measure = {
  vw: number
  vh: number
  docScroll: string
  sideOverflow: number
  bottomBars: Bar[]
  overlaps: string[]
  seeThrough: string[]
  bottomStack: number
  scrollers: string[]
}

// Runs in the page (serialized: it must not close over anything). The same measurements as the scratch mobile check
// it replaces, except that a "bottom bar" must be a bar (40×16 px or more), so an empty, collapsed toast host pinned
// to the bottom is not counted as a bar off the screen.
const MEASURE = (): Measure => {
  const vw = innerWidth
  const vh = innerHeight
  const se = document.scrollingElement!
  const name = (el: Element) => {
    const c = (el.getAttribute('class') ?? '').trim().split(/\s+/).slice(0, 2).join('.')
    const label = el.getAttribute('aria-label')
    return `${el.tagName.toLowerCase()}${c ? '.' + c : ''}${label ? `[${label.slice(0, 24)}]` : ''}`
  }
  const all = [...document.querySelectorAll('body *')]
  const shown = (cs: CSSStyleDeclaration) => cs.display !== 'none' && cs.visibility !== 'hidden' && +cs.opacity !== 0
  const pinnedEls = all.filter((el) => {
    const cs = getComputedStyle(el)
    return (cs.position === 'fixed' || cs.position === 'sticky') && shown(cs)
  })
  // a bottom bar: pinned by its bottom edge (bottom: 0, sticky bottom), bar-sized
  const bottomBars = pinnedEls
    .filter((el) => {
      const r = el.getBoundingClientRect()
      return getComputedStyle(el).bottom !== 'auto' && r.width >= 40 && r.height >= 16
    })
    .map((el) => {
      const r = el.getBoundingClientRect()
      return { sel: name(el), top: Math.round(r.top), bottom: Math.round(r.bottom), onScreen: r.bottom <= vh + 1 && r.top < vh }
    })
  // pinned things covering part of each other (neither inside the other), both on the screen
  const overlaps: string[] = []
  const tall = pinnedEls.filter((el) => el.getBoundingClientRect().height > 16)
  for (let i = 0; i < tall.length; i++)
    for (let j = i + 1; j < tall.length; j++) {
      const a = tall[i]!
      const b = tall[j]!
      if (a.contains(b) || b.contains(a)) continue
      const ra = a.getBoundingClientRect()
      const rb = b.getBoundingClientRect()
      const ix = Math.min(ra.right, rb.right) - Math.max(ra.left, rb.left)
      const iy = Math.min(ra.bottom, rb.bottom) - Math.max(ra.top, rb.top)
      if (ix > 20 && iy > 6 && ra.top < vh && rb.top < vh && ra.bottom > 0 && rb.bottom > 0) overlaps.push(`${name(a)} × ${name(b)} (${Math.round(iy)}px)`)
    }
  // pinned things you can see through: content scrolls visibly behind them (reported, not asserted)
  const seeThrough = pinnedEls
    .filter((el) => {
      const cs = getComputedStyle(el)
      const r = el.getBoundingClientRect()
      if (r.height < 16 || r.bottom <= 0 || r.top >= vh) return false
      const m = cs.backgroundColor.match(/[\d.]+/g) ?? ['0', '0', '0', '0']
      const alpha = m.length > 3 ? +m[3]! : 1
      return alpha < 0.85 && (cs.backdropFilter === 'none' || !cs.backdropFilter)
    })
    .map(name)
  // how much of the screen the bottom-pinned bars take together, in % (reported, not asserted)
  const bottomStack = Math.round(
    (all
      .filter((el) => {
        const cs = getComputedStyle(el)
        if (cs.display === 'none') return false
        const r = el.getBoundingClientRect()
        return (cs.position === 'fixed' || cs.position === 'sticky' || /tabs|dock|bar/.test(el.className?.toString?.() ?? '')) && r.bottom >= vh - 2 && r.top > vh * 0.4 && r.width > vw * 0.8
      })
      .reduce((m, el) => Math.max(m, vh - el.getBoundingClientRect().top), 0) /
      vh) *
      100,
  )
  const scrollers = all
    .filter((el) => /(auto|scroll)/.test(getComputedStyle(el).overflowY) && el.scrollHeight > el.clientHeight + 4)
    .map((el) => `${name(el)} ${Math.round(el.scrollTop)}/${el.scrollHeight - el.clientHeight}`)
  return {
    vw,
    vh,
    docScroll: `${Math.round(se.scrollTop)}/${se.scrollHeight - se.clientHeight}`,
    sideOverflow: se.scrollWidth > vw + 1 ? se.scrollWidth : 0,
    bottomBars,
    overlaps: overlaps.slice(0, 12),
    seeThrough: [...new Set(seeThrough)].slice(0, 8),
    bottomStack,
    scrollers: scrollers.slice(0, 6),
  }
}

/** the page counts as drawn once the DOM stops changing for ~1 s with nothing aria-busy (at most 20 s) */
const SETTLE = () =>
  new Promise<boolean>((done) => {
    let last = ''
    let same = 0
    const end = Date.now() + 20_000
    const tick = () => {
      const now = `${document.querySelectorAll('*').length}:${document.body?.innerText.length ?? 0}:${!!document.querySelector('[aria-busy="true"]')}`
      same = now === last && !now.endsWith('true') ? same + 1 : 0
      last = now
      if (same >= 4 || Date.now() > end) return done(same >= 4)
      setTimeout(tick, 250)
    }
    tick()
  })

const engineHandle = () => {
  const engine = Reflect.get(globalThis, ENGINE_KEY) as ReturnType<typeof web> | undefined
  const live = engine && surfaceOf(engine)
  if (!live) throw new Error('mobile checks need the web engine e2e.config.ts registers (ENGINE_KEY)')
  return live
}

describe('mobile layout', { tags: ['mobile'] }, () => {
  for (const [id, path, heading, signedIn] of SCREENS) {
    test(`phone: ${id}`, { timeout: 120_000 }, async ({ app, browser, screen }) => {
      // sign in through the control server (a fresh context per test), or open the app signed out, then become a
      // Pixel 7 before the screen loads
      await app.open(signedIn ? as('owner', '/') : `${APP_URL}/login`)
      await browser.setViewport(PIXEL_7.viewport)
      const live = engineHandle()
      const page = live.page()
      const cdp = await live.context().newCDPSession(page)
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        ...PIXEL_7.viewport,
        deviceScaleFactor: PIXEL_7.deviceScaleFactor,
        mobile: true,
        screenWidth: PIXEL_7.screen.width,
        screenHeight: PIXEL_7.screen.height,
      })
      await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 })
      await cdp.send('Emulation.setUserAgentOverride', { userAgent: PIXEL_7.userAgent, platform: 'Android' })

      await browser.goto(`${APP_URL}${path}`)
      // still on the screen asked for (not bounced to sign-in or elsewhere), and laid out for a phone
      await expect(browser).toHaveURL(new RegExp(`127\\.0\\.0\\.1:\\d+${path.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(\\?|$)`))
      await expect(screen.getByRole('heading', heading, { level: 1 })).toBeVisible()
      expect(await browser.evaluate(() => matchMedia('(pointer: coarse)').matches && innerWidth === 412)).toBe(true)
      // A layout check on a page that never loaded proves nothing: the screen must settle, must not be an error page,
      // and must have drawn real content (no skeleton left) before anything is measured.
      expect(await browser.evaluate(SETTLE)).toBe(true)
      const loaded = await browser.evaluate(() => {
        const text = document.body?.innerText ?? ''
        return {
          chars: text.trim().length,
          controls: document.querySelectorAll('button, a[href], input, textarea, select').length,
          skeletons: document.querySelectorAll('.skeleton, [aria-busy="true"]').length,
          broken: /\bnot[ _]found\b|unauthorized|something went wrong|failed to load|internal server error/i.exec(text)?.[0] ?? null,
        }
      })
      expect(loaded.broken).toBe(null)
      expect(loaded.skeletons).toBe(0)
      expect(loaded.chars).toBeGreaterThan(80)
      expect(loaded.controls).toBeGreaterThan(3)

      const swipe = async (from: number, to: number) => {
        const x = Math.round(PIXEL_7.viewport.width / 2)
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y: from }] })
        for (let i = 1; i <= 12; i++) {
          await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: Math.round(from + ((to - from) * i) / 12) }] })
          await page.waitForTimeout(16)
        }
        await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
        await page.waitForTimeout(700)
      }
      const vh = PIXEL_7.viewport.height
      const states: Record<string, Measure> = {}
      states.top = await browser.evaluate(MEASURE)
      for (let k = 0; k < 3; k++) await swipe(Math.round(vh * 0.75), Math.round(vh * 0.25))
      states.scrolled = await browser.evaluate(MEASURE)
      for (let k = 0; k < 8; k++) await swipe(Math.round(vh * 0.8), Math.round(vh * 0.15))
      states.end = await browser.evaluate(MEASURE)
      await cdp.detach()

      const problems: string[] = []
      for (const [when, m] of Object.entries(states)) {
        if (m.sideOverflow) problems.push(`${when}: the page scrolls sideways (${m.sideOverflow}px wide on a ${m.vw}px screen)`)
        const off = m.bottomBars.filter((b) => !b.onScreen)
        if (off.length) problems.push(`${when}: bottom bar off the screen: ${off.map((b) => `${b.sel} ${b.top}–${b.bottom}`).join(', ')}`)
        if (m.overlaps.length) problems.push(`${when}: pinned bars overlap: ${m.overlaps.join('; ')}`)
      }
      // seeThrough / bottomStack / scrollers ride along in the message, for whoever reads a red run
      if (problems.length) throw new Error(`${id} (${path}) on a Pixel 7:\n- ${problems.join('\n- ')}\n${JSON.stringify(states, null, 1).slice(0, 4000)}`)
    })
  }
})
