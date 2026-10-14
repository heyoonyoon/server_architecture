type EntryGuideProps = { onEnter: () => void }

export function EntryGuide({ onEnter }: EntryGuideProps) {
  return (
    <section className="entry">
      <button type="button" className="entry-button" onClick={onEnter}>
        수강신청 진입
      </button>
      <div className="entry-guide">
        <h2>수강신청 진입 안내</h2>
        <p>진입 방법: '수강신청 진입' 버튼을 눌러 수강신청 화면으로 이동하시기 바랍니다.</p>
        <p className="caution">수강신청 진입 시 대기 화면이 뜰 경우 기다려주시기 바랍니다.</p>
        <p>재접속해도 대기 순번은 유지됩니다.</p>
      </div>
    </section>
  )
}
