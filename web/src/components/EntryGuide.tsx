import { formatDuration, formatTimeOfDay } from '../entities/duration'
import { useCountdown } from '../hooks/useCountdown'

type EntryGuideProps = {
  openAt: number | null
  onEnter: () => void
}

type EntryButtonProps = {
  remaining: number
  onEnter: () => void
}

const ignoreExpiry = () => undefined

function EntryButton({ remaining, onEnter }: EntryButtonProps) {
  const waiting = remaining > 0
  return (
    <button type="button" className="entry-button" onClick={onEnter} disabled={waiting}>
      {waiting ? (
        <>
          수강신청 진입까지 <span className="entry-countdown">{formatDuration(remaining)}</span>
        </>
      ) : (
        '수강신청 진입'
      )}
    </button>
  )
}

function TimedEntryButton({ openAt, onEnter }: { openAt: number; onEnter: () => void }) {
  const remaining = useCountdown(openAt, ignoreExpiry)
  return <EntryButton remaining={remaining} onEnter={onEnter} />
}

export function EntryGuide({ openAt, onEnter }: EntryGuideProps) {
  return (
    <section className="entry">
      {openAt === null ? (
        <EntryButton remaining={0} onEnter={onEnter} />
      ) : (
        <TimedEntryButton openAt={openAt} onEnter={onEnter} />
      )}
      <div className="entry-guide">
        <h2>수강신청 진입 안내</h2>
        {openAt !== null && <p>수강신청 진입 가능시간 : {formatTimeOfDay(openAt)}부터</p>}
        <p>진입 방법: '수강신청 진입' 버튼을 눌러 수강신청 화면으로 이동하시기 바랍니다.</p>
        <p className="caution">수강신청 진입 시 대기 화면이 뜰 경우 기다려주시기 바랍니다.</p>
        <p>재접속해도 대기 순번은 유지됩니다.</p>
      </div>
    </section>
  )
}
