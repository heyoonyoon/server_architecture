export type Role = 'ROLE_UNSPECIFIED' | 'STUDENT' | 'PROFESSOR'

export type StudentDto = {
  studentId: string
  name: string
  department: string
  completedCourseIds: string[]
}

export type CourseDto = {
  courseId: string
  courseName: string
  instructorName: string
  prerequisiteCourseIds: string[]
}

export type LoginRequest = { userId: string; password: string }

export type LoginResponse = {
  success: boolean
  token: string
  userId: string
  name: string
  role: Role | string
}

export type ResultResponse = { success: boolean }

export type StudentListResponse = ResultResponse & { students?: Partial<StudentDto>[] }

export type CourseListResponse = ResultResponse & { courses?: Partial<CourseDto>[] }

export function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

export function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}
