import { studentApi } from '../api/students'
import { useEntityCollection, type EntityMessages } from './useEntityCollection'

const studentMessages: EntityMessages = {
  added: '학생을 추가했습니다.',
  duplicate: '이미 존재하는 학번입니다.',
  deleted: '학생을 삭제했습니다.',
  missing: '존재하지 않는 학번입니다.',
}

export function useStudents() {
  return useEntityCollection(studentApi, studentMessages)
}
