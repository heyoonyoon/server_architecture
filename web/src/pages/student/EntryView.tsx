import { courseApi } from '../../api/courses'
import { CourseCatalog } from '../../components/CourseCatalog'
import { EntryGuide } from '../../components/EntryGuide'
import type { Notifier } from '../../hooks/useNotice'
import { useRemoteList } from '../../hooks/useRemoteList'

type EntryViewProps = {
  notifier: Notifier
  onEnter: () => void
}

export function EntryView({ notifier, onEnter }: EntryViewProps) {
  const { items: courses, loading, refresh } = useRemoteList(courseApi.list, notifier.notifyError)

  return (
    <div className="entry-page">
      <EntryGuide onEnter={onEnter} />
      <CourseCatalog courses={courses} loading={loading} onRefresh={() => void refresh()} />
    </div>
  )
}
