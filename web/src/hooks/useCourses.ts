import { courseApi } from '../api/courses'
import { useEntityCollection, type EntityMessages } from './useEntityCollection'

const courseMessages: EntityMessages = {
  added: '과목을 추가했습니다.',
  duplicate: '이미 존재하는 과목 번호입니다.',
  deleted: '과목을 삭제했습니다.',
  missing: '존재하지 않는 과목 번호입니다.',
}

export function useCourses() {
  return useEntityCollection(courseApi, courseMessages)
}
