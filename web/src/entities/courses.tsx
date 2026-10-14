import type { CourseDraft } from '../api/courses'
import type { CourseDto } from '../api/dto'
import type { Column } from '../components/DataTable'
import type { EntityView } from '../components/EntityManager'
import { IdList } from '../components/IdList'
import { splitIds } from './ids'

type CourseField = 'courseId' | 'courseName' | 'instructorName' | 'prerequisiteCourseIds'

export const courseKey = (course: CourseDto) => course.courseId

export const courseColumns: readonly Column<CourseDto>[] = [
  { label: '과목 번호', render: (course) => course.courseId },
  { label: '과목 이름', render: (course) => course.courseName },
  { label: '담당 교수', render: (course) => course.instructorName },
  { label: '선수 과목', render: (course) => <IdList ids={course.prerequisiteCourseIds} /> },
]

export const courseView: EntityView<CourseDto, CourseField, CourseDraft> = {
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
