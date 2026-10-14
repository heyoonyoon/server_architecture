import type { StudentInfo } from '../gen/server_client_pb'
import { api } from './client'
import type { EntityApi } from './entityApi'

export type StudentDraft = {
  studentId: string
  name: string
  department: string
  completedCourseIds: string[]
}

export const studentApi: EntityApi<StudentInfo, StudentDraft> = {
  list: async () => (await api.getStudentListController({})).students,
  add: async (student) => (await api.addStudentController({ student })).success,
  remove: async (studentId) => (await api.deleteStudentController({ studentId })).success,
}
