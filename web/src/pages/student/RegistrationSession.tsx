import { useCallback } from 'react'
import { QueueWaiting } from '../../components/QueueWaiting'
import type { Notifier } from '../../hooks/useNotice'
import { useQueue } from '../../hooks/useQueue'
import { EnrollmentView } from './EnrollmentView'

const PASS_TIMEOUT_MESSAGE = '입장 시간이 만료되었습니다. 대기열에 다시 들어갑니다.'

type RegistrationSessionProps = { notifier: Notifier }

export function RegistrationSession({ notifier }: RegistrationSessionProps) {
  const { notify, notifyError } = notifier
  const { status, rejoin, refreshPass } = useQueue(notifyError)

  const returnToQueue = useCallback(
    (text: string) => {
      notify({ kind: 'warn', text })
      rejoin()
    },
    [notify, rejoin],
  )

  const handlePassTimeout = useCallback(() => returnToQueue(PASS_TIMEOUT_MESSAGE), [returnToQueue])

  return status?.state === 'ADMITTED' ? (
    <EnrollmentView
      notifier={notifier}
      passExpiresAt={status.passExpiresAt}
      onPassTimeout={handlePassTimeout}
      onPassExpired={returnToQueue}
      onActivity={refreshPass}
    />
  ) : (
    <QueueWaiting status={status} />
  )
}
