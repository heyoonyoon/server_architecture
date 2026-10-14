import { useCallback } from 'react'
import { MessageBanner } from '../components/MessageBanner'
import { QueueWaiting } from '../components/QueueWaiting'
import { useNotice } from '../hooks/useNotice'
import { useQueue } from '../hooks/useQueue'
import { EnrollmentView } from './student/EnrollmentView'

const PASS_TIMEOUT_MESSAGE = '입장 시간이 만료되었습니다. 대기열에 다시 들어갑니다.'

export function StudentPage() {
  const { notice, dismiss, notifier } = useNotice()
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

  return (
    <>
      <MessageBanner notice={notice} onDismiss={dismiss} />
      {status?.state === 'ADMITTED' ? (
        <EnrollmentView
          notifier={notifier}
          passExpiresAt={status.passExpiresAt}
          onPassTimeout={handlePassTimeout}
          onPassExpired={returnToQueue}
          onActivity={refreshPass}
        />
      ) : (
        <QueueWaiting status={status} />
      )}
    </>
  )
}
