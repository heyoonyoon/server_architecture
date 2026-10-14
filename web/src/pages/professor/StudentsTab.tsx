import { EntityManager } from '../../components/EntityManager'
import { studentView } from '../../entities/students'
import { useStudents } from '../../hooks/useStudents'

export function StudentsTab() {
  const students = useStudents()
  return <EntityManager view={studentView} collection={students} />
}
