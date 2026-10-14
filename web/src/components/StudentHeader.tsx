import { useCallback } from 'react'
import { SESSION_EXPIRED_MESSAGE } from '../api/errors'
import { authStore } from '../auth/authStore'
import type { Session } from '../auth/session'
import { tokenExpiresAt } from '../auth/token'
import { LoginTimer } from './LoginTimer'
import { UserMenu } from './UserMenu'

type StudentHeaderProps = {
  session: Session
  onLogout: () => void
}

export function StudentHeader({ session, onLogout }: StudentHeaderProps) {
  const { token, user } = session
  const expiresAt = tokenExpiresAt(token)
  const handleExpire = useCallback(() => authStore.expire(token, SESSION_EXPIRED_MESSAGE), [token])

  return (
    <header className="student-header">
      {expiresAt !== null && <LoginTimer expiresAt={expiresAt} onExpire={handleExpire} />}
      <div className="brand-row">
        <div className="brand">
          <strong>수강신청시스템</strong>
          <span>Course Registration System</span>
        </div>
        <UserMenu user={user} onLogout={onLogout} />
      </div>
    </header>
  )
}
