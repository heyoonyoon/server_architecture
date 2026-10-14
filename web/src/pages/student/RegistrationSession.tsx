import { useCallback, useEffect } from 'react'
import { queueApi } from '../../api/enrollment'
import { describeError, isFailedPrecondition } from '../../api/errors'
import { QueueWaiting } from '../../components/QueueWaiting'
import type { Notice, Notifier } from '../../hooks/useNotice'
import { useQueue } from '../../hooks/useQueue'
import { EnrollmentView } from './EnrollmentView'

const PASS_TIMEOUT_MESSAGE = '입장 시간이 만료되었습니다. 대기열에 다시 들어갑니다.'

const FINISHED_MESSAGE = '수강신청을 종료했습니다.'

type RegistrationSessionProps = {
  notifier: Notifier
  onExit: (notice: Notice) => void
}

function releaseOnUnload() {
  void queueApi.leave(true).catch(() => undefined)
}

export function RegistrationSession({ notifier, onExit }: RegistrationSessionProps) {
  const { notify, notifyError } = notifier

  const handleQueueError = useCallback(
    (error: unknown) => (isFailedPrecondition(error) ? onExit({ kind: 'warn', text: error.message }) : notifyError(error)),
    [onExit, notifyError],
  )

  const { status, rejoin, refreshPass } = useQueue(handleQueueError)
  const admitted = status?.state === 'ADMITTED'

  useEffect(() => {
    if (!admitted) return
    window.addEventListener('pagehide', releaseOnUnload)
    return () => window.removeEventListener('pagehide', releaseOnUnload)
  }, [admitted])

  const returnToQueue = useCallback(
    (text: string) => {
      notify({ kind: 'warn', text })
      rejoin()
    },
    [notify, rejoin],
  )

  const handlePassTimeout = useCallback(() => returnToQueue(PASS_TIMEOUT_MESSAGE), [returnToQueue])

  const finish = useCallback(async () => {
    try {
      await queueApi.leave()
      onExit({ kind: 'success', text: FINISHED_MESSAGE })
    } catch (error) {
      onExit({ kind: 'warn', text: `${FINISHED_MESSAGE} 입장권 반환에 실패했습니다. (${describeError(error)})` })
    }
  }, [onExit])

  return admitted ? (
    <EnrollmentView
      notifier={notifier}
      passExpiresAt={status.passExpiresAt}
      onPassTimeout={handlePassTimeout}
      onPassExpired={returnToQueue}
      onActivity={refreshPass}
      onFinish={() => void finish()}
    />
  ) : (
    <QueueWaiting status={status} />
  )
}
