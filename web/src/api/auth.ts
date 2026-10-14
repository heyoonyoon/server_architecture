import type { Credentials, Session } from '../auth/session'
import { isUserRole } from '../auth/roles'
import { text, type LoginRequest, type LoginResponse } from './dto'
import { http } from './http'

export async function requestLogin({ userId, password }: Credentials): Promise<Session | null> {
  const request: LoginRequest = { userId, password }
  const response = await http.post<Partial<LoginResponse>>('/login', request, { authenticated: false })
  const token = text(response.token)
  if (response.success !== true || token === '' || !isUserRole(response.role)) {
    return null
  }
  return {
    token,
    user: { id: text(response.userId), name: text(response.name), role: response.role },
  }
}
