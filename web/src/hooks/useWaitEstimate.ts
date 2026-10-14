import { useEffect, useRef, useState } from 'react'

type Sample = { at: number; ahead: number }

export function useWaitEstimate(ahead: number | null): number | null {
  const origin = useRef<Sample | null>(null)
  const [estimate, setEstimate] = useState<number | null>(null)

  useEffect(() => {
    if (ahead === null) return
    const now = Date.now()
    const start = origin.current
    if (!start || ahead > start.ahead) {
      origin.current = { at: now, ahead }
      return
    }
    const served = start.ahead - ahead
    setEstimate(served > 0 ? Math.ceil((ahead * (now - start.at)) / served / 1000) : null)
  }, [ahead])

  return estimate
}
