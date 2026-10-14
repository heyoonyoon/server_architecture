import type { OfferingDto } from '../api/dto'
import type { Column } from '../components/DataTable'
import { IdList } from '../components/IdList'
import { formatSchedule } from './schedule'

export const offeringKey = (offering: OfferingDto) => String(offering.offeringId)

export const enrolledOfferingColumns: readonly Column<OfferingDto>[] = [
  { label: '과목 번호', render: (offering) => offering.courseId },
  { label: '과목명', render: (offering) => offering.courseName },
  { label: '분반', render: (offering) => offering.section },
  { label: '학점', render: (offering) => offering.credits },
  { label: '담당 교수', render: (offering) => offering.professorName },
  { label: '시간', render: (offering) => formatSchedule(offering.times) || <span className="muted">미정</span> },
  { label: '강의실', render: (offering) => offering.room || <span className="muted">미정</span> },
]

export const offeringColumns: readonly Column<OfferingDto>[] = [
  ...enrolledOfferingColumns,
  {
    label: '남은 자리/정원',
    render: (offering) => (
      <span className={offering.remaining === 0 ? 'seats full' : 'seats'}>
        {offering.remaining}/{offering.capacity}
      </span>
    ),
  },
  { label: '선수 과목', render: (offering) => <IdList ids={offering.prerequisiteCourseIds} /> },
]
