import { useCallback, useMemo } from 'react'
import type { MyEnrollmentsDto, OfferingDto } from '../api/dto'
import { enrollmentApi, type ActionResult } from '../api/enrollment'
import { isPassExpired } from '../api/errors'
import { useBusyAction } from './useBusyAction'
import type { Notifier } from './useNotice'
import { useRemoteData } from './useRemoteData'

export type EnrollmentBoard = {
  offerings: OfferingDto[]
  mine: MyEnrollmentsDto
}

export type EnrollmentCallbacks = {
  onPassExpired: (message: string) => void
  onActivity: () => Promise<void>
}

const emptyBoard: EnrollmentBoard = {
  offerings: [],
  mine: { offerings: [], totalCredits: 0, maxCredits: 0 },
}

async function loadBoard(): Promise<EnrollmentBoard> {
  const [offerings, mine] = await Promise.all([enrollmentApi.listOfferings(), enrollmentApi.listMine()])
  return { offerings, mine }
}

export function useEnrollment({ notify, notifyError }: Notifier, { onPassExpired, onActivity }: EnrollmentCallbacks) {
  const handleError = useCallback(
    (error: unknown) => (isPassExpired(error) ? onPassExpired(error.message) : notifyError(error)),
    [onPassExpired, notifyError],
  )
  const load = useCallback(async () => {
    const board = await loadBoard()
    await onActivity()
    return board
  }, [onActivity])
  const { data: board, loading, refresh } = useRemoteData(load, emptyBoard, handleError)
  const { busy, run } = useBusyAction(handleError)

  const enrolledIds = useMemo(
    () => new Set(board.mine.offerings.map((offering) => offering.offeringId)),
    [board.mine.offerings],
  )

  const perform = useCallback(
    async (action: () => Promise<ActionResult>) => {
      await run(async () => {
        const { success, message } = await action()
        notify({ kind: success ? 'success' : 'warn', text: message })
        await refresh()
      })
    },
    [run, notify, refresh],
  )

  const enroll = useCallback((offeringId: number) => perform(() => enrollmentApi.enroll(offeringId)), [perform])
  const cancel = useCallback((offeringId: number) => perform(() => enrollmentApi.cancel(offeringId)), [perform])

  return { board, enrolledIds, loading, busy, reload: refresh, enroll, cancel }
}
