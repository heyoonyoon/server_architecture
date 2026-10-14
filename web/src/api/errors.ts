export const SESSION_EXPIRED_MESSAGE = '로그인이 만료되었습니다. 다시 로그인하세요.'

const PERMISSION_DENIED_MESSAGE = '이 작업을 수행할 권한이 없습니다.'

export const TIMEOUT_MESSAGE = '서버 응답 시간이 초과되었습니다.'

export const NETWORK_ERROR_MESSAGE = '서버에 연결할 수 없습니다.'

export const NETWORK_ERROR_STATUS = 0

const FAILED_PRECONDITION = 9

export class ApiError extends Error {
  readonly status: number
  readonly code: number | null

  constructor(status: number, message: string, code: number | null = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export function isUnauthenticated(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401
}

export function isPassExpired(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 400 && error.code === FAILED_PRECONDITION
}

export function describeError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return `서버 오류: ${error instanceof Error ? error.message : String(error)}`
  }
  switch (error.status) {
    case 401:
      return SESSION_EXPIRED_MESSAGE
    case 403:
      return PERMISSION_DENIED_MESSAGE
    case NETWORK_ERROR_STATUS:
      return error.message
    default:
      return `서버 오류: ${error.message}`
  }
}
