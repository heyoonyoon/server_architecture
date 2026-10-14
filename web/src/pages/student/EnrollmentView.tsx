import { useState } from 'react'
import { ConfirmButton } from '../../components/ConfirmButton'
import { EnrollmentSummary } from '../../components/EnrollmentSummary'
import { OfferingList } from '../../components/OfferingList'
import { PassBar } from '../../components/PassBar'
import { Sheet } from '../../components/Sheet'
import { Tabs, type TabItem } from '../../components/Tabs'
import { useEnrollment, type EnrollmentCallbacks } from '../../hooks/useEnrollment'
import type { Notifier } from '../../hooks/useNotice'

type EnrollmentTab = 'offerings' | 'mine'

const tabs: readonly TabItem<EnrollmentTab>[] = [
  { key: 'offerings', label: '개설강좌' },
  { key: 'mine', label: '수강신청내역' },
]

type EnrollmentViewProps = EnrollmentCallbacks & {
  notifier: Notifier
  passExpiresAt: number
  onPassTimeout: () => void
  onFinish: () => void
}

export function EnrollmentView({
  notifier,
  passExpiresAt,
  onPassTimeout,
  onFinish,
  ...callbacks
}: EnrollmentViewProps) {
  const [tab, setTab] = useState<EnrollmentTab>('offerings')
  const { board, enrolledIds, loading, busy, reload, enroll, cancel } = useEnrollment(notifier, callbacks)

  return (
    <>
      <EnrollmentSummary mine={board.mine} />
      <div className="session-bar">
        <PassBar expiresAt={passExpiresAt} onExpire={onPassTimeout} />
        <ConfirmButton
          className="finish"
          message="수강신청을 종료하시겠습니까? 입장권이 반환되며, 다시 진입하면 대기열에 새로 들어갑니다."
          onConfirm={onFinish}
          disabled={busy}
        >
          수강신청 종료
        </ConfirmButton>
      </div>
      <div className="sheet-tabs">
        <Tabs items={tabs} active={tab} onChange={setTab} />
      </div>
      {tab === 'offerings' ? (
        <Sheet
          title={`개설강좌 (${board.offerings.length})`}
          note="※ 빠르게 '수강신청' 버튼을 클릭하세요!"
          loading={loading}
          onRefresh={() => void reload()}
        >
          <OfferingList
            offerings={board.offerings}
            loading={loading}
            empty="개설강좌가 없습니다."
            renderAction={(offering) => {
              const enrolled = enrolledIds.has(offering.offeringId)
              return (
                <button
                  type="button"
                  className="apply"
                  onClick={() => void enroll(offering.offeringId)}
                  disabled={busy || enrolled}
                >
                  {enrolled ? '신청완료' : '수강신청'}
                </button>
              )
            }}
          />
        </Sheet>
      ) : (
        <Sheet
          title={`수강신청내역 (${board.mine.offerings.length})`}
          note="※ 취소한 강좌는 '개설강좌' 탭에서 다시 신청할 수 있습니다."
          loading={loading}
          onRefresh={() => void reload()}
        >
          <OfferingList
            offerings={board.mine.offerings}
            loading={loading}
            empty="수강신청한 강좌가 없습니다."
            renderAction={(offering) => (
              <ConfirmButton
                className="withdraw"
                message={`${offering.courseName} (${offering.section}분반) 신청을 취소하시겠습니까?`}
                onConfirm={() => void cancel(offering.offeringId)}
                disabled={busy}
              >
                신청 취소
              </ConfirmButton>
            )}
          />
        </Sheet>
      )}
    </>
  )
}
