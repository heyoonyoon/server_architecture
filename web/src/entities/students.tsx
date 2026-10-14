import type { StudentDraft } from '../api/students'
import type { EntityView } from '../components/EntityManager'
import { IdList } from '../components/IdList'
import type { StudentInfo } from '../gen/server_client_pb'
import { splitIds } from './ids'

type StudentField = 'studentId' | 'name' | 'department' | 'completedCourseIds'

export const studentView: EntityView<StudentInfo, StudentField, StudentDraft> = {
  itemLabel: '학생',
  idLabel: '학번',
  rowKey: (student) => student.studentId,
  columns: [
    { label: '학번', render: (student) => student.studentId },
    { label: '이름', render: (student) => student.name },
    { label: '학과', render: (student) => student.department },
    { label: '이수 과목', render: (student) => <IdList ids={student.completedCourseIds} /> },
  ],
  form: {
    fields: [
      { key: 'studentId', label: '학번', required: true },
      { key: 'name', label: '이름', required: true },
      { key: 'department', label: '학과', required: true },
      { key: 'completedCourseIds', label: '이수 과목', required: false, placeholder: '예: 12345 23456' },
    ],
    toDraft: (values) => ({
      studentId: values.studentId,
      name: values.name,
      department: values.department,
      completedCourseIds: splitIds(values.completedCourseIds),
    }),
  },
}
