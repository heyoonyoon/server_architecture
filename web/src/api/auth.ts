import type { Credentials, Session } from '../auth/session'
import { isUserRole } from '../auth/roles'
import { api } from './client'

export async function requestLogin({ userId, password }: Credentials): Promise<Session | null> {
  const response = await api.loginController({ userId, password })
  if (!response.success || !isUserRole(response.role)) {
    return null
  }
  return {
    token: response.token,
    user: { id: response.userId, name: response.name, role: response.role },
  }
}
