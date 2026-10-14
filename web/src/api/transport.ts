import type { Interceptor } from '@connectrpc/connect'
import { createGrpcWebTransport } from '@connectrpc/connect-web'
import { authStore } from '../auth/authStore'
import { isUnauthenticated, SESSION_EXPIRED_MESSAGE } from './errors'

const baseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

const authInterceptor: Interceptor = (next) => async (request) => {
  const token = authStore.getState().session?.token
  if (token) {
    request.header.set('authorization', `Bearer ${token}`)
  }
  try {
    return await next(request)
  } catch (error) {
    if (token && isUnauthenticated(error)) {
      authStore.expire(token, SESSION_EXPIRED_MESSAGE)
    }
    throw error
  }
}

export const transport = createGrpcWebTransport({
  baseUrl,
  defaultTimeoutMs: 5000,
  interceptors: [authInterceptor],
})
