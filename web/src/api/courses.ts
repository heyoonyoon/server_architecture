import { stringList, text, type CourseDto, type CourseListResponse, type ResultResponse } from './dto'
import type { EntityApi } from './entityApi'
import { http } from './http'

export type CourseDraft = CourseDto

function toCourse(course: Partial<CourseDto>): CourseDto {
  return {
    courseId: text(course.courseId),
    courseName: text(course.courseName),
    instructorName: text(course.instructorName),
    prerequisiteCourseIds: stringList(course.prerequisiteCourseIds),
  }
}

export const courseApi: EntityApi<CourseDto, CourseDraft> = {
  list: async () => ((await http.get<CourseListResponse>('/courses')).courses ?? []).map(toCourse),
  add: async (course) => (await http.post<ResultResponse>('/courses', course)).success === true,
  remove: async (courseId) =>
    (await http.delete<ResultResponse>(`/courses/${encodeURIComponent(courseId)}`)).success === true,
}
