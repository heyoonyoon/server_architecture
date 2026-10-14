import { stringList, text, type ResultResponse, type StudentDto, type StudentListResponse } from './dto'
import type { EntityApi } from './entityApi'
import { http } from './http'

export type StudentDraft = StudentDto

function toStudent(student: Partial<StudentDto>): StudentDto {
  return {
    studentId: text(student.studentId),
    name: text(student.name),
    department: text(student.department),
    completedCourseIds: stringList(student.completedCourseIds),
  }
}

export const studentApi: EntityApi<StudentDto, StudentDraft> = {
  list: async () => ((await http.get<StudentListResponse>('/students')).students ?? []).map(toStudent),
  add: async (student) => (await http.post<ResultResponse>('/students', student)).success === true,
  remove: async (studentId) =>
    (await http.delete<ResultResponse>(`/students/${encodeURIComponent(studentId)}`)).success === true,
}
