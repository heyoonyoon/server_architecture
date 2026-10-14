import { useCallback, useMemo } from 'react'
import type { MyEnrollmentsDto, OfferingDto } from '../api/dto'
import { enrollmentApi, type ActionResult } from '../api/enrollment'
import { alertError, isPassExpired } from '../api/errors'
import { useBusyAction } from './useBusyAction'
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

export function useEnrollment({ onPassExpired, onActivity }: EnrollmentCallbacks) {
  const handleError = useCallback(
    (error: unknown) => (isPassExpired(error) ? onPassExpired(error.message) : alertError(error)),
    [onPassExpired],
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
    async (action: () => Promise<ActionResult>, successMessage: string) => {
      await run(async () => {
        const { success, message } = await action()
        window.alert(success ? successMessage : message)
        await refresh()
      })
    },
    [run, refresh],
  )

  const enroll = useCallback(
    ({ offeringId, courseName }: OfferingDto) =>
      perform(() => enrollmentApi.enroll(offeringId), `${courseName}의 수강신청이 완료되었습니다.`),
    [perform],
  )
  const cancel = useCallback(
    ({ offeringId, courseName }: OfferingDto) =>
      perform(() => enrollmentApi.cancel(offeringId), `${courseName}의 수강신청이 취소되었습니다.`),
    [perform],
  )

  return { board, enrolledIds, loading, busy, reload: refresh, enroll, cancel }
}
