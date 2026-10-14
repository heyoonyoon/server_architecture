import { EntityManager } from '../../components/EntityManager'
import { courseView } from '../../entities/courses'
import { useCourses } from '../../hooks/useCourses'
import type { Notifier } from '../../hooks/useNotice'

export function CoursesTab({ notifier }: { notifier: Notifier }) {
  const courses = useCourses(notifier)
  return <EntityManager view={courseView} collection={courses} notify={notifier.notify} />
}
