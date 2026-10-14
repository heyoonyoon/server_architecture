import { useLogin } from '../auth/useLogin'
import { LoginForm } from '../components/LoginForm'
import { MessageBanner } from '../components/MessageBanner'

type LoginPageProps = {
  signOutReason: string | null
}

export function LoginPage({ signOutReason }: LoginPageProps) {
  const { submitting, error, login } = useLogin()

  return (
    <main className="login-page">
      <section className="login-card">
        <h1>수강신청</h1>
        <p className="muted">학번 또는 교번으로 로그인하세요.</p>
        <MessageBanner notice={signOutReason ? { kind: 'warn', text: signOutReason } : null} />
        <LoginForm submitting={submitting} error={error} onSubmit={(credentials) => void login(credentials)} />
      </section>
    </main>
  )
}
