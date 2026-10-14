import { useState } from 'react'
import { MessageBanner } from '../components/MessageBanner'
import { Tabs, type TabItem } from '../components/Tabs'
import { useNotice } from '../hooks/useNotice'
import { CoursesTab } from './professor/CoursesTab'
import { StudentsTab } from './professor/StudentsTab'

type ProfessorTab = 'students' | 'courses'

const tabs: readonly TabItem<ProfessorTab>[] = [
  { key: 'students', label: '학생 목록' },
  { key: 'courses', label: '과목 목록' },
]

export function ProfessorPage() {
  const [tab, setTab] = useState<ProfessorTab>('students')
  const { notice, dismiss, notifier } = useNotice()

  return (
    <>
      <div className="toolbar">
        <Tabs items={tabs} active={tab} onChange={setTab} />
      </div>
      <MessageBanner notice={notice} onDismiss={dismiss} />
      {tab === 'students' ? <StudentsTab notifier={notifier} /> : <CoursesTab notifier={notifier} />}
    </>
  )
}
