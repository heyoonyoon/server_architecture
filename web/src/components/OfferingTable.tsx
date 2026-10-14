import type { OfferingDto } from '../api/dto'
import { offeringColumns, offeringKey } from '../entities/offerings'
import { DataTable } from './DataTable'
import { Panel } from './Panel'

type OfferingTableProps = {
  offerings: readonly OfferingDto[]
  enrolledIds: ReadonlySet<number>
  loading: boolean
  busy: boolean
  onRefresh: () => void
  onEnroll: (offeringId: number) => void
}

export function OfferingTable({ offerings, enrolledIds, loading, busy, onRefresh, onEnroll }: OfferingTableProps) {
  return (
    <Panel title="개설 강의" loading={loading} onRefresh={onRefresh}>
      <DataTable
        columns={offeringColumns}
        rows={offerings}
        rowKey={offeringKey}
        loading={loading}
        renderActions={(offering) => {
          const enrolled = enrolledIds.has(offering.offeringId)
          return (
            <button type="button" onClick={() => onEnroll(offering.offeringId)} disabled={busy || enrolled}>
              {enrolled ? '신청 완료' : '신청'}
            </button>
          )
        }}
      />
    </Panel>
  )
}
