import { useEffect, useState } from 'react'

const TICK_MS = 1000

export function usePassCountdown(expiresAt: number, onExpire: () => void): number {
  const [now, setNow] = useState(() => Date.now())
  const remaining = Math.max(0, Math.ceil((expiresAt - now) / 1000))

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(timer)
  }, [])

  useEffect(() => {
    if (remaining === 0) onExpire()
  }, [remaining, onExpire])

  return remaining
}
