import { formatDuration } from '../entities/duration'
import { useCountdown } from '../hooks/useCountdown'

const WARNING_SECONDS = 60

type PassBarProps = {
  expiresAt: number
  onExpire: () => void
}

export function PassBar({ expiresAt, onExpire }: PassBarProps) {
  const remaining = useCountdown(expiresAt, onExpire)
  return (
    <div className={remaining <= WARNING_SECONDS ? 'pass-bar expiring' : 'pass-bar'} role="timer">
      <span>
        입장권 남은 시간 <strong>{formatDuration(remaining)}</strong>
      </span>
      <span className="muted">신청, 취소, 새로고침 시 연장됩니다.</span>
    </div>
  )
}
