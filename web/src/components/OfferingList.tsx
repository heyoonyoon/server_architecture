import type { ReactNode } from 'react'
import type { OfferingDto } from '../api/dto'
import { enrolledCount, isFull, offeringKey } from '../entities/offerings'
import { formatSchedule } from '../entities/schedule'
import { ItemCard } from './ItemCard'
import { ItemList } from './ItemList'

type OfferingListProps = {
  offerings: readonly OfferingDto[]
  loading: boolean
  empty: string
  renderAction: (offering: OfferingDto) => ReactNode
}

function creditLine(offering: OfferingDto): string {
  const prerequisites = offering.prerequisiteCourseIds
  const base = `학점 : ${offering.credits} / 담당교수 : ${offering.professorName}`
  return prerequisites.length ? `${base} / 선수과목 : ${prerequisites.join(', ')}` : base
}

function offeringLines(offering: OfferingDto): ReactNode[] {
  return [
    creditLine(offering),
    `${formatSchedule(offering.times) || '시간 미정'}(${offering.room || '강의실 미정'})`,
    <span className={isFull(offering) ? 'seats full' : 'seats'}>
      수강인원 : {enrolledCount(offering)} / 제한인원 : {offering.capacity}
    </span>,
  ]
}

export function OfferingList({ offerings, loading, empty, renderAction }: OfferingListProps) {
  return (
    <ItemList loading={loading} empty={empty}>
      {offerings.map((offering) => (
        <ItemCard
          key={offeringKey(offering)}
          title={
            <>
              {offering.courseId} {offering.courseName}
              <span className="item-sub"> {offering.section}분반</span>
            </>
          }
          lines={offeringLines(offering)}
          action={renderAction(offering)}
        />
      ))}
    </ItemList>
  )
}
