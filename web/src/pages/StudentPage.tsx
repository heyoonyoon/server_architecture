import { useState } from 'react'
import type { Session } from '../auth/session'
import { MessageBanner } from '../components/MessageBanner'
import { StudentHeader } from '../components/StudentHeader'
import { useNotice } from '../hooks/useNotice'
import { EntryView } from './student/EntryView'
import { RegistrationSession } from './student/RegistrationSession'

type StudentPageProps = {
  session: Session
  onLogout: () => void
}

export function StudentPage({ session, onLogout }: StudentPageProps) {
  const [entered, setEntered] = useState(false)
  const { notice, dismiss, notifier } = useNotice()

  function enter() {
    dismiss()
    setEntered(true)
  }

  return (
    <div className="student-app">
      <StudentHeader session={session} onLogout={onLogout} />
      <main className="student-main">
        {entered ? <RegistrationSession notifier={notifier} /> : <EntryView notifier={notifier} onEnter={enter} />}
      </main>
      <MessageBanner notice={notice} onDismiss={dismiss} floating />
    </div>
  )
}
