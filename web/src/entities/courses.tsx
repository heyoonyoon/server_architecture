import type { CourseDraft } from '../api/courses'
import type { Column } from '../components/DataTable'
import type { EntityView } from '../components/EntityManager'
import { IdList } from '../components/IdList'
import type { CourseInfo } from '../gen/server_client_pb'
import { splitIds } from './ids'

type CourseField = 'courseId' | 'courseName' | 'instructorName' | 'prerequisiteCourseIds'

export const courseKey = (course: CourseInfo) => course.courseId

export const courseColumns: readonly Column<CourseInfo>[] = [
  { label: '과목 번호', render: (course) => course.courseId },
  { label: '과목 이름', render: (course) => course.courseName },
  { label: '담당 교수', render: (course) => course.instructorName },
  { label: '선수 과목', render: (course) => <IdList ids={course.prerequisiteCourseIds} /> },
]

export const courseView: EntityView<CourseInfo, CourseField, CourseDraft> = {
  itemLabel: '과목',
  idLabel: '과목 번호',
  rowKey: courseKey,
  columns: courseColumns,
  form: {
    fields: [
      { key: 'courseId', label: '과목 번호', required: true },
      { key: 'courseName', label: '과목 이름', required: true },
      { key: 'instructorName', label: '담당 교수', required: true },
      { key: 'prerequisiteCourseIds', label: '선수 과목', required: false, placeholder: '예: 12345 23456' },
    ],
    toDraft: (values) => ({
      courseId: values.courseId,
      courseName: values.courseName,
      instructorName: values.instructorName,
      prerequisiteCourseIds: splitIds(values.prerequisiteCourseIds),
    }),
  },
}
