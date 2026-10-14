import { useAuth } from './auth/useAuth'
import { AppHeader } from './components/AppHeader'
import { LoginPage } from './pages/LoginPage'
import { ProfessorPage } from './pages/ProfessorPage'
import { StudentPage } from './pages/StudentPage'

export default function App() {
  const { session, signOutReason, signOut } = useAuth()

  if (!session) {
    return <LoginPage signOutReason={signOutReason} />
  }

  const { user } = session

  if (user.role === 'STUDENT') {
    return <StudentPage key={user.id} session={session} onLogout={signOut} />
  }

  return (
    <div className="app">
      <AppHeader title="수강신청 관리" user={user} onLogout={signOut} />
      <main key={user.id}>
        <ProfessorPage />
      </main>
    </div>
  )
}
