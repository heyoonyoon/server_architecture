import { Code, ConnectError } from '@connectrpc/connect'

export const SESSION_EXPIRED_MESSAGE = '로그인이 만료되었습니다. 다시 로그인하세요.'

const PERMISSION_DENIED_MESSAGE = '이 작업을 수행할 권한이 없습니다.'

export function isUnauthenticated(error: unknown): boolean {
  return ConnectError.from(error).code === Code.Unauthenticated
}

export function describeError(error: unknown): string {
  const connectError = ConnectError.from(error)
  switch (connectError.code) {
    case Code.Unauthenticated:
      return SESSION_EXPIRED_MESSAGE
    case Code.PermissionDenied:
      return PERMISSION_DENIED_MESSAGE
    default:
      return `서버 오류: ${connectError.message}`
  }
}
