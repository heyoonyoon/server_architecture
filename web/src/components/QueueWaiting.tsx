import { useState } from 'react'
import type { QueueStatusDto } from '../api/dto'
import { formatWait } from '../entities/duration'
import { useWaitEstimate } from '../hooks/useWaitEstimate'

type QueueWaitingProps = { status: QueueStatusDto | null }

function progressPercent(ahead: number, peak: number): number {
  return peak === 0 ? 100 : Math.round(((peak - ahead) / peak) * 100)
}

export function QueueWaiting({ status }: QueueWaitingProps) {
  const waiting = status?.state === 'WAITING'
  const ahead = waiting ? Math.max(0, status.position - 1) : null
  const [peak, setPeak] = useState(0)
  if (ahead !== null && ahead > peak) setPeak(ahead)
  const estimate = useWaitEstimate(ahead)
  const percent = ahead === null ? 0 : progressPercent(ahead, peak)

  return (
    <section className="queue-box" aria-live="polite">
      <div className="queue-inner">
        <p className="queue-title">서비스 접속대기 중입니다.</p>
        <p className="queue-title">
          예상대기시간 : {ahead === null ? '' : estimate === null ? '계산 중' : formatWait(estimate)}
        </p>
        <div className="queue-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent}>
          <div className="queue-bar-fill" style={{ width: `${percent}%` }} />
        </div>
        <p className="queue-ahead">
          {ahead === null ? (
            '대기열에 입장하는 중입니다.'
          ) : (
            <>
              학생 앞에 <strong>{ahead.toLocaleString()}</strong> 명의 대기자가 있습니다.
            </>
          )}
        </p>
        <p>현재 접속 사용자가 많아 대기 중이며, 잠시만 기다리시면 서비스로 자동 접속 됩니다.</p>
        <p className="queue-warn">※ 재접속해도 대기 순번은 유지됩니다.</p>
      </div>
    </section>
  )
}
