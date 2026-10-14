import { useEffect, useState } from 'react'
import { scheduleApi } from '../api/enrollment'

const REFRESH_MS = 5000

export function useSchedule(): number | null {
  const [openAt, setOpenAt] = useState<number | null>(null)

  useEffect(() => {
    let active = true
    let timer: ReturnType<typeof setTimeout> | undefined

    async function refresh() {
      const sentAt = Date.now()
      const schedule = await scheduleApi.get().catch(() => null)
      const receivedAt = Date.now()
      if (!active) return
      if (schedule) {
        const { openAtMs, serverNowMs } = schedule
        const offset = serverNowMs > 0 ? serverNowMs - (sentAt + receivedAt) / 2 : 0
        setOpenAt(openAtMs > 0 ? Math.round(openAtMs - offset) : null)
      }
      timer = setTimeout(() => void refresh(), REFRESH_MS)
    }

    void refresh()
    return () => {
      active = false
      clearTimeout(timer)
    }
  }, [])

  return openAt
}
