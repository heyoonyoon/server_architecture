import { authStore } from '../auth/authStore'
import {
  ApiError,
  isUnauthenticated,
  NETWORK_ERROR_MESSAGE,
  NETWORK_ERROR_STATUS,
  SESSION_EXPIRED_MESSAGE,
  TIMEOUT_MESSAGE,
} from './errors'

const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/+$/, '')

const TIMEOUT_MS = 5000

type Method = 'GET' | 'POST' | 'DELETE'

type RequestOptions = {
  body?: unknown
  authenticated?: boolean
}

async function errorMessage(response: Response): Promise<string> {
  const raw = await response.text().catch(() => '')
  try {
    const parsed: unknown = JSON.parse(raw)
    if (typeof parsed === 'object' && parsed !== null) {
      const { message } = parsed as Record<string, unknown>
      if (typeof message === 'string' && message !== '') return message
    }
  } catch {
    if (raw.trim() !== '' && raw.length <= 200) return raw.trim()
  }
  return response.statusText || `HTTP ${response.status}`
}

async function send<T>(method: Method, path: string, { body, authenticated = true }: RequestOptions): Promise<T> {
  const token = authenticated ? authStore.getState().session?.token : undefined
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers.Authorization = `Bearer ${token}`

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS)
  try {
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
    })
    if (!response.ok) {
      throw new ApiError(response.status, await errorMessage(response))
    }
    return (await response.json()) as T
  } catch (error) {
    if (error instanceof ApiError) {
      if (token && isUnauthenticated(error)) authStore.expire(token, SESSION_EXPIRED_MESSAGE)
      throw error
    }
    if (controller.signal.aborted) throw new ApiError(NETWORK_ERROR_STATUS, TIMEOUT_MESSAGE)
    throw new ApiError(NETWORK_ERROR_STATUS, NETWORK_ERROR_MESSAGE)
  } finally {
    clearTimeout(timer)
  }
}

export const http = {
  get: <T>(path: string) => send<T>('GET', path, {}),
  post: <T>(path: string, body: unknown, options: Omit<RequestOptions, 'body'> = {}) =>
    send<T>('POST', path, { ...options, body }),
  delete: <T>(path: string) => send<T>('DELETE', path, {}),
}
