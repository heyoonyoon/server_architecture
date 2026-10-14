import { roleLabels } from '../auth/roles'
import type { User } from '../auth/session'

type AppHeaderProps = {
  title: string
  user: User
  onLogout: () => void
}

export function AppHeader({ title, user, onLogout }: AppHeaderProps) {
  return (
    <header className="app-header">
      <h1>{title}</h1>
      <div className="user-menu">
        <span className="user-name">
          {user.name} ({roleLabels[user.role]})
        </span>
        <button type="button" className="secondary" onClick={onLogout}>
          로그아웃
        </button>
      </div>
    </header>
  )
}
