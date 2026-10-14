import { useCallback } from 'react'
import { queueApi } from '../api/enrollment'
import type { Session } from '../auth/session'
import { MessageBanner } from '../components/MessageBanner'
import { StudentHeader } from '../components/StudentHeader'
import { useEntryFlag } from '../hooks/useEntryFlag'
import { useNotice, type Notice } from '../hooks/useNotice'
import { EntryView } from './student/EntryView'
import { RegistrationSession } from './student/RegistrationSession'

type StudentPageProps = {
  session: Session
  onLogout: () => void
}

export function StudentPage({ session, onLogout }: StudentPageProps) {
  const [entered, setEntered] = useEntryFlag(session.user.id)
  const { notice, dismiss, notifier } = useNotice()
  const { notify } = notifier

  function enter() {
    dismiss()
    setEntered(true)
  }

  const exit = useCallback(
    (next: Notice) => {
      setEntered(false)
      notify(next)
    },
    [setEntered, notify],
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
        {entered ? (
          <RegistrationSession notifier={notifier} onExit={exit} />
        ) : (
          <EntryView notifier={notifier} onEnter={enter} />
        )}
      </main>
      <MessageBanner notice={notice} onDismiss={dismiss} floating />
    </div>
  )
}
