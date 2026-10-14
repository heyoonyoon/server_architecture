import { formatDuration } from '../entities/duration'
import { useCountdown } from '../hooks/useCountdown'

const WARNING_SECONDS = 300

type LoginTimerProps = {
  expiresAt: number
  onExpire: () => void
}

export function LoginTimer({ expiresAt, onExpire }: LoginTimerProps) {
  const remaining = useCountdown(expiresAt, onExpire)
  return (
    <p className={remaining <= WARNING_SECONDS ? 'login-timer expiring' : 'login-timer'} role="timer">
      로그인 남은 시간<strong>{formatDuration(remaining)}</strong>
    </p>
  )
}
