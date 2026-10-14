import { useState } from 'react'
import { Tabs, type TabItem } from '../components/Tabs'
import { CoursesTab } from './professor/CoursesTab'
import { StudentsTab } from './professor/StudentsTab'

type ProfessorTab = 'students' | 'courses'

const tabs: readonly TabItem<ProfessorTab>[] = [
  { key: 'students', label: '학생 목록' },
  { key: 'courses', label: '과목 목록' },
]

export function ProfessorPage() {
  const [tab, setTab] = useState<ProfessorTab>('students')

  return (
    <>
      <div className="toolbar">
        <Tabs items={tabs} active={tab} onChange={setTab} />
      </div>
      {tab === 'students' ? <StudentsTab /> : <CoursesTab />}
    </>
  )
}
