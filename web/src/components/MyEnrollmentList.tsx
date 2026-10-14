import type { MyEnrollmentsDto } from '../api/dto'
import { enrolledOfferingColumns, offeringKey } from '../entities/offerings'
import { ConfirmButton } from './ConfirmButton'
import { DataTable } from './DataTable'
import { Panel } from './Panel'

type MyEnrollmentListProps = {
  enrollments: MyEnrollmentsDto
  loading: boolean
  busy: boolean
  onRefresh: () => void
  onCancel: (offeringId: number) => void
}

export function MyEnrollmentList({ enrollments, loading, busy, onRefresh, onCancel }: MyEnrollmentListProps) {
  const { offerings, totalCredits, maxCredits } = enrollments
  return (
    <Panel title="내 신청 내역" loading={loading} onRefresh={onRefresh}>
      <DataTable
        columns={enrolledOfferingColumns}
        rows={offerings}
        rowKey={offeringKey}
        loading={loading}
        renderActions={(offering) => (
          <ConfirmButton
            className="danger"
            message={`${offering.courseName} (${offering.section}분반) 신청을 취소하시겠습니까?`}
            onConfirm={() => onCancel(offering.offeringId)}
            disabled={busy}
          >
            취소
          </ConfirmButton>
        )}
      />
      <p className="credit-summary">
        신청 학점 <strong>{totalCredits}</strong> / {maxCredits}
      </p>
    </Panel>
  )
}
