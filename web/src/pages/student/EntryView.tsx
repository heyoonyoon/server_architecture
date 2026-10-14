import { courseApi } from '../../api/courses'
import { alertError } from '../../api/errors'
import { CourseCatalog } from '../../components/CourseCatalog'
import { EntryGuide } from '../../components/EntryGuide'
import { useRemoteList } from '../../hooks/useRemoteList'
import { useSchedule } from '../../hooks/useSchedule'

type EntryViewProps = {
  onEnter: () => void
}

export function EntryView({ onEnter }: EntryViewProps) {
  const openAt = useSchedule()
  const { items: courses, loading, refresh } = useRemoteList(courseApi.list, alertError)

  return (
    <div className="entry-page">
      <EntryGuide openAt={openAt} onEnter={onEnter} />
      <CourseCatalog courses={courses} loading={loading} onRefresh={() => void refresh()} />
    </div>
  )
}
