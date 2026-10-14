import { EntityManager } from '../../components/EntityManager'
import { courseView } from '../../entities/courses'
import { useCourses } from '../../hooks/useCourses'

export function CoursesTab() {
  const courses = useCourses()
  return <EntityManager view={courseView} collection={courses} />
}
