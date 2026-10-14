import { MyEnrollmentList } from '../../components/MyEnrollmentList'
import { OfferingTable } from '../../components/OfferingTable'
import { PassBar } from '../../components/PassBar'
import { useEnrollment, type EnrollmentCallbacks } from '../../hooks/useEnrollment'
import type { Notifier } from '../../hooks/useNotice'

type EnrollmentViewProps = EnrollmentCallbacks & {
  notifier: Notifier
  passExpiresAt: number
  onPassTimeout: () => void
}

export function EnrollmentView({ notifier, passExpiresAt, onPassTimeout, ...callbacks }: EnrollmentViewProps) {
  const { board, enrolledIds, loading, busy, reload, enroll, cancel } = useEnrollment(notifier, callbacks)

  return (
    <>
      <PassBar expiresAt={passExpiresAt} onExpire={onPassTimeout} />
      <div className="stack">
        <OfferingTable
          offerings={board.offerings}
          enrolledIds={enrolledIds}
          loading={loading}
          busy={busy}
          onRefresh={() => void reload()}
          onEnroll={(offeringId) => void enroll(offeringId)}
        />
        <MyEnrollmentList
          enrollments={board.mine}
          loading={loading}
          busy={busy}
          onRefresh={() => void reload()}
          onCancel={(offeringId) => void cancel(offeringId)}
        />
      </div>
    </>
  )
}
