import { DataTable } from '../components/DataTable'
import { MessageBanner } from '../components/MessageBanner'
import { Panel } from '../components/Panel'
import { courseColumns, courseKey } from '../entities/courses'
import { useCourseList } from '../hooks/useCourses'
import { useNotice } from '../hooks/useNotice'

export function StudentPage() {
  const { notice, dismiss, notifier } = useNotice()
  const { items, loading, refresh } = useCourseList(notifier)

  return (
    <>
      <MessageBanner notice={notice} onDismiss={dismiss} />
      <Panel title="개설 과목" loading={loading} onRefresh={() => void refresh()}>
        <p className="note" role="note">
          수강신청 기능은 준비 중입니다.
        </p>
        <DataTable columns={courseColumns} rows={items} rowKey={courseKey} loading={loading} />
      </Panel>
    </>
  )
}
