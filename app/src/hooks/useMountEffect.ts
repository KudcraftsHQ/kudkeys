import { useEffect } from 'react'

/**
 * Run once on mount, clean up on unmount. The ONLY allowed useEffect
 * pattern in this codebase — reserved for external system sync (DOM APIs,
 * subscriptions, EventSource, timers, etc.). Per the project React rules.
 */
// eslint-disable-next-line react-hooks/exhaustive-deps
export const useMountEffect = (fn: () => void | (() => void)) => useEffect(fn, [])
