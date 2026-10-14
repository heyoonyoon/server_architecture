import type { CourseInfo } from '../gen/server_client_pb'
import { api } from './client'
import type { EntityApi } from './entityApi'

export type CourseDraft = {
  courseId: string
  courseName: string
  instructorName: string
  prerequisiteCourseIds: string[]
}

export const courseApi: EntityApi<CourseInfo, CourseDraft> = {
  list: async () => (await api.getCourseListController({})).courses,
  add: async (course) => (await api.addCourseController({ course })).success,
  remove: async (courseId) => (await api.deleteCourseController({ courseId })).success,
}
