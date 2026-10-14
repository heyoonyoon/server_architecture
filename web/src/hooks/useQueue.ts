import { useCallback, useEffect, useState } from 'react'
import type { QueueStatusDto } from '../api/dto'
import { queueApi } from '../api/enrollment'

const DEFAULT_POLL_MS = 2000

export type QueueSnapshot = QueueStatusDto & { passExpiresAt: number }

function snapshot(status: QueueStatusDto): QueueSnapshot {
  return { ...status, passExpiresAt: Date.now() + status.passRemainingSeconds * 1000 }
}

function pollDelay(status: QueueStatusDto | null): number {
  return status && status.nextPollMs > 0 ? status.nextPollMs : DEFAULT_POLL_MS
}

export function useQueue(onError: (error: unknown) => void) {
  const [status, setStatus] = useState<QueueSnapshot | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let active = true
    let timer: ReturnType<typeof setTimeout> | undefined
    let pending: () => Promise<QueueStatusDto> = queueApi.enter

    async function step(request: () => Promise<QueueStatusDto>) {
      timer = undefined
      let next: QueueStatusDto | null = null
      try {
        next = await request()
      } catch (error) {
        if (active) onError(error)
      }
      if (!active) return
      if (next) setStatus(snapshot(next))
      if (next?.state === 'ADMITTED') return
      pending = next?.state === 'WAITING' ? queueApi.status : next ? queueApi.enter : request
      timer = setTimeout(() => void step(pending), pollDelay(next))
    }

    function pollNow() {
      if (document.visibilityState !== 'visible' || timer === undefined) return
      clearTimeout(timer)
      void step(pending)
    }

    void step(queueApi.enter)
    document.addEventListener('visibilitychange', pollNow)
    return () => {
      active = false
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', pollNow)
    }
  }, [attempt, onError])

  const rejoin = useCallback(() => {
    setStatus(null)
    setAttempt((count) => count + 1)
  }, [])

  const refreshPass = useCallback(async () => {
    try {
      const next = await queueApi.status()
      if (next.state === 'ADMITTED') {
        setStatus(snapshot(next))
      } else {
        rejoin()
      }
    } catch (error) {
      onError(error)
    }
  }, [onError, rejoin])

  return { status, rejoin, refreshPass }
}
