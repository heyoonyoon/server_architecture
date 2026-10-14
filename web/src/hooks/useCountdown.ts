import { useEffect, useState } from 'react'

const TICK_MS = 1000

export function useCountdown(expiresAt: number, onExpire: () => void): number {
  const [now, setNow] = useState(() => Date.now())
  const remaining = Math.max(0, Math.ceil((expiresAt - now) / 1000))

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    const delay = expiresAt - Date.now()
    if (delay <= 0) return
    const timer = setTimeout(() => setNow(Date.now()), delay)
    return () => clearTimeout(timer)
  }, [expiresAt])

  useEffect(() => {
    if (remaining === 0) onExpire()
  }, [remaining, onExpire])

  return remaining
}
