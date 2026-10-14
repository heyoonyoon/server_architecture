import type { User } from '../auth/session'

type UserMenuProps = {
  user: User
  onLogout: () => void
}

export function UserMenu({ user, onLogout }: UserMenuProps) {
  return (
    <details className="user-box">
      <summary>
        <span className="user-box-name">{user.name}</span>
        <span className="user-box-avatar" aria-hidden="true">
          {user.name.slice(0, 1)}
        </span>
        <span className="user-box-caret" aria-hidden="true" />
      </summary>
      <div className="user-box-menu">
        <p>
          <span className="muted">학번</span> {user.id}
        </p>
        <button type="button" onClick={onLogout}>
          로그아웃
        </button>
      </div>
    </details>
  )
}
