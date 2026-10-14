import { useCallback, useState } from 'react'
import { api, splitIds } from './api'
import { Panel, type Column, type Field, type Notice } from './Panel'
import type { CourseInfo, StudentInfo } from './gen/server_client_pb'

type Tab = 'students' | 'courses'

function idList(ids: string[]) {
  return ids.length ? ids.join(', ') : <span className="muted">없음</span>
}

const studentColumns: Column<StudentInfo>[] = [
  { label: '학번', render: (s) => s.studentId },
  { label: '이름', render: (s) => s.name },
  { label: '학과', render: (s) => s.department },
  { label: '이수 과목', render: (s) => idList(s.completedCourseIds) },
]

const studentFields: Field[] = [
  { key: 'id', label: '학번', required: true },
  { key: 'name', label: '이름', required: true },
  { key: 'department', label: '학과', required: true },
  { key: 'courses', label: '이수 과목', required: false, placeholder: '예: 12345 23456' },
]

const courseColumns: Column<CourseInfo>[] = [
  { label: '과목 번호', render: (c) => c.courseId },
  { label: '과목 이름', render: (c) => c.courseName },
  { label: '담당 교수', render: (c) => c.instructorName },
  { label: '선수 과목', render: (c) => idList(c.prerequisiteCourseIds) },
]

const courseFields: Field[] = [
  { key: 'id', label: '과목 번호', required: true },
  { key: 'name', label: '과목 이름', required: true },
  { key: 'instructor', label: '담당 교수', required: true },
  { key: 'prerequisites', label: '선수 과목', required: false, placeholder: '예: 12345 23456' },
]

const loadStudents = async () => (await api.getStudentListController({})).students
const loadCourses = async () => (await api.getCourseListController({})).courses

const addStudent = async (v: Record<string, string>) =>
  (
    await api.addStudentController({
      student: { studentId: v.id, name: v.name, department: v.department, completedCourseIds: splitIds(v.courses) },
    })
  ).success

const addCourse = async (v: Record<string, string>) =>
  (
    await api.addCourseController({
      course: {
        courseId: v.id,
        courseName: v.name,
        instructorName: v.instructor,
        prerequisiteCourseIds: splitIds(v.prerequisites),
      },
    })
  ).success

const removeStudent = async (studentId: string) => (await api.deleteStudentController({ studentId })).success
const removeCourse = async (courseId: string) => (await api.deleteCourseController({ courseId })).success

export default function App() {
  const [tab, setTab] = useState<Tab>('students')
  const [notice, setNotice] = useState<Notice | null>(null)
  const onNotice = useCallback((n: Notice) => setNotice(n), [])

  return (
    <div className="app">
      <header>
        <h1>수강신청 관리</h1>
        <nav className="tabs" role="tablist">
          <button type="button" role="tab" aria-selected={tab === 'students'} onClick={() => setTab('students')}>
            학생 목록
          </button>
          <button type="button" role="tab" aria-selected={tab === 'courses'} onClick={() => setTab('courses')}>
            과목 목록
          </button>
        </nav>
      </header>

      {notice && (
        <div className={`banner ${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
          <span>{notice.kind === 'error' ? `서버 오류: ${notice.text}` : notice.text}</span>
          <button type="button" className="close" aria-label="닫기" onClick={() => setNotice(null)}>
            ×
          </button>
        </div>
      )}

      <main>
        {tab === 'students' ? (
          <Panel
            key="students"
            title="학생 목록"
            idLabel="학번"
            columns={studentColumns}
            fields={studentFields}
            rowId={(s) => s.studentId}
            load={loadStudents}
            add={addStudent}
            remove={removeStudent}
            messages={{
              added: '학생을 추가했습니다.',
              duplicate: '이미 존재하는 학번입니다.',
              deleted: '학생을 삭제했습니다.',
              missing: '존재하지 않는 학번입니다.',
            }}
            onNotice={onNotice}
          />
        ) : (
          <Panel
            key="courses"
            title="과목 목록"
            idLabel="과목 번호"
            columns={courseColumns}
            fields={courseFields}
            rowId={(c) => c.courseId}
            load={loadCourses}
            add={addCourse}
            remove={removeCourse}
            messages={{
              added: '과목을 추가했습니다.',
              duplicate: '이미 존재하는 과목 번호입니다.',
              deleted: '과목을 삭제했습니다.',
              missing: '존재하지 않는 과목 번호입니다.',
            }}
            onNotice={onNotice}
          />
        )}
      </main>
    </div>
  )
}
