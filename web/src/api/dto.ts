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

export type QueueState = 'NOT_IN_QUEUE' | 'WAITING' | 'ADMITTED'

export type QueueStatusDto = {
  state: QueueState
  position: number
  nextPollMs: number
  passRemainingSeconds: number
}

export type OfferingTimeDto = {
  dayOfWeek: string
  startTime: string
  endTime: string
}

export type OfferingDto = {
  offeringId: number
  courseId: string
  courseName: string
  credits: number
  semester: string
  section: string
  professorId: string
  professorName: string
  room: string
  capacity: number
  remaining: number
  times: OfferingTimeDto[]
  prerequisiteCourseIds: string[]
}

export type MyEnrollmentsDto = {
  offerings: OfferingDto[]
  totalCredits: number
  maxCredits: number
}

export type ScheduleDto = {
  openAtMs: number
  serverNowMs: number
}

export type ScheduleResponse = { openAtMs?: number | string; serverNowMs?: number | string }

export type LoginRequest = { userId: string; password: string }

export type LoginResponse = {
  success: boolean
  token: string
  userId: string
  name: string
  role: Role | string
}

export type ResultResponse = { success: boolean }

export type MessageResponse = ResultResponse & { message: string }

export type EnrollRequest = { offeringId: number }

export type QueueStatusResponse = Partial<Omit<QueueStatusDto, 'state'>> & { state?: string }

export type OfferingResponse = Partial<Omit<OfferingDto, 'times'>> & { times?: Partial<OfferingTimeDto>[] }

export type OfferingListResponse = { offerings?: OfferingResponse[] }

export type MyEnrollmentsResponse = OfferingListResponse & Partial<Omit<MyEnrollmentsDto, 'offerings'>>

export type StudentListResponse = ResultResponse & { students?: Partial<StudentDto>[] }

export type CourseListResponse = ResultResponse & { courses?: Partial<CourseDto>[] }

export function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

export function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

export function millis(value: unknown): number {
  const parsed = typeof value === 'string' ? Number(value) : value
  return typeof parsed === 'number' && Number.isFinite(parsed) ? parsed : 0
}

export function integer(value: unknown): number {
  return typeof value === 'number' && Number.isInteger(value) ? value : 0
}
