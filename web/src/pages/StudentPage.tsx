import { useCallback } from 'react'
import { queueApi } from '../api/enrollment'
import type { Session } from '../auth/session'
import { StudentHeader } from '../components/StudentHeader'
import { useEntryFlag } from '../hooks/useEntryFlag'
import { EntryView } from './student/EntryView'
import { RegistrationSession } from './student/RegistrationSession'

type StudentPageProps = {
  session: Session
  onLogout: () => void
}

export function StudentPage({ session, onLogout }: StudentPageProps) {
  const [entered, setEntered] = useEntryFlag(session.user.id)

  const exit = useCallback(
    (message: string) => {
      window.alert(message)
      setEntered(false)
    },
    [setEntered],
  )

  function logout() {
    void queueApi.leave(true).catch(() => undefined)
    setEntered(false)
    onLogout()
  }

  return (
    <div className="student-app">
      <StudentHeader session={session} onLogout={logout} />
      <main className="student-main">
        {entered ? <RegistrationSession onExit={exit} /> : <EntryView onEnter={() => setEntered(true)} />}
      </main>
    </div>
  )
}
