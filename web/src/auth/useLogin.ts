import { useCallback, useState } from 'react'
import { requestLogin } from '../api/auth'
import { describeError } from '../api/errors'
import { authStore } from './authStore'
import type { Credentials } from './session'

const INVALID_CREDENTIALS_MESSAGE = '아이디 또는 비밀번호가 올바르지 않습니다.'

export function useLogin() {
  const [submitting, setSubmitting] = useState(false)

  const login = useCallback(async (credentials: Credentials) => {
    setSubmitting(true)
    try {
      const session = await requestLogin(credentials)
      if (session) {
        authStore.signIn(session)
      } else {
        window.alert(INVALID_CREDENTIALS_MESSAGE)
      }
    } catch (e) {
      window.alert(describeError(e))
    } finally {
      setSubmitting(false)
    }
  }, [])

  return { submitting, login }
}
