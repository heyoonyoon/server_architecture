import type { QueueStatusDto } from '../api/dto'

type QueueWaitingProps = { status: QueueStatusDto | null }

export function QueueWaiting({ status }: QueueWaitingProps) {
  const waiting = status?.state === 'WAITING'
  return (
    <section className="panel queue-waiting" aria-live="polite">
      <p className="muted">수강신청 대기 중</p>
      <p className="queue-position">{waiting ? `대기 순번 ${status.position}번` : '대기열에 입장하는 중…'}</p>
      <p className="muted">순서가 되면 자동으로 수강신청 화면으로 이동합니다. 새로고침해도 순번은 유지됩니다.</p>
    </section>
  )
}
