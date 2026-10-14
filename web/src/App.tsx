import { useAuth } from './auth/useAuth'
import { AppHeader } from './components/AppHeader'
import { Role } from './gen/server_client_pb'
import { LoginPage } from './pages/LoginPage'
import { ProfessorPage } from './pages/ProfessorPage'
import { StudentPage } from './pages/StudentPage'

export default function App() {
  const { session, signOutReason, signOut } = useAuth()

  if (!session) {
    return <LoginPage signOutReason={signOutReason} />
  }

  const { user } = session
  const isProfessor = user.role === Role.PROFESSOR

  return (
    <div className="app">
      <AppHeader title={isProfessor ? '수강신청 관리' : '수강신청'} user={user} onLogout={signOut} />
      <main key={user.id}>{isProfessor ? <ProfessorPage /> : <StudentPage />}</main>
    </div>
  )
}
