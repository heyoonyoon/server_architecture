import { isUserRole, type UserRole } from './roles'

export type Credentials = { userId: string; password: string }

export type User = { id: string; name: string; role: UserRole }

export type Session = { token: string; user: User }

const STORAGE_KEY = 'course-registration.session'

function isUser(value: unknown): value is User {
  if (typeof value !== 'object' || value === null) return false
  const { id, name, role } = value as Record<string, unknown>
  return typeof id === 'string' && typeof name === 'string' && isUserRole(role)
}

function isSession(value: unknown): value is Session {
  if (typeof value !== 'object' || value === null) return false
  const { token, user } = value as Record<string, unknown>
  return typeof token === 'string' && token.length > 0 && isUser(user)
}

export function readStoredSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    return isSession(parsed) ? parsed : null
  } catch {
    return null
  }
}

export function writeStoredSession(session: Session): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session))
  } catch {
    return
  }
}

export function clearStoredSession(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY)
  } catch {
    return
  }
}
