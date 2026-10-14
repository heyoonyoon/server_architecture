import { usePassCountdown } from '../hooks/usePassCountdown'

const WARNING_SECONDS = 60

type PassBarProps = {
  expiresAt: number
  onExpire: () => void
}

function formatDuration(totalSeconds: number): string {
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return `${minutes}:${String(seconds).padStart(2, '0')}`
}

export function PassBar({ expiresAt, onExpire }: PassBarProps) {
  const remaining = usePassCountdown(expiresAt, onExpire)
  return (
    <div className={remaining <= WARNING_SECONDS ? 'pass-bar expiring' : 'pass-bar'} role="timer">
      <span>
        입장권 남은 시간 <strong>{formatDuration(remaining)}</strong>
      </span>
      <span className="muted">신청·취소·새로고침 시 연장됩니다.</span>
    </div>
  )
}
