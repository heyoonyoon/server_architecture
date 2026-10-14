import { useLogin } from '../auth/useLogin'
import { LoginForm } from '../components/LoginForm'

export function LoginPage() {
  const { submitting, login } = useLogin()

  return (
    <main className="login-page">
      <section className="login-card">
        <h1>수강신청</h1>
        <p className="muted">학번 또는 교번으로 로그인하세요.</p>
        <LoginForm submitting={submitting} onSubmit={(credentials) => void login(credentials)} />
      </section>
    </main>
  )
}
