import { useState, type FormEvent } from 'react'
import type { Credentials } from '../auth/session'

type LoginFormProps = {
  submitting: boolean
  onSubmit: (credentials: Credentials) => void
}

export function LoginForm({ submitting, onSubmit }: LoginFormProps) {
  const [userId, setUserId] = useState('')
  const [password, setPassword] = useState('')
  const canSubmit = !submitting && userId.trim() !== '' && password !== ''

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (canSubmit) onSubmit({ userId: userId.trim(), password })
  }

  return (
    <form className="login-form" onSubmit={handleSubmit}>
      <label>
        <span>아이디</span>
        <input
          value={userId}
          onChange={(event) => setUserId(event.target.value)}
          autoComplete="username"
          autoFocus
          disabled={submitting}
        />
      </label>
      <label>
        <span>비밀번호</span>
        <input
          type="password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          autoComplete="current-password"
          disabled={submitting}
        />
      </label>
      <button type="submit" disabled={!canSubmit}>
        {submitting ? '로그인 중…' : '로그인'}
      </button>
    </form>
  )
}
