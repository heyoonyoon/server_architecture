import { EntityManager } from '../../components/EntityManager'
import { studentView } from '../../entities/students'
import type { Notifier } from '../../hooks/useNotice'
import { useStudents } from '../../hooks/useStudents'

export function StudentsTab({ notifier }: { notifier: Notifier }) {
  const students = useStudents(notifier)
  return <EntityManager view={studentView} collection={students} notify={notifier.notify} />
}
