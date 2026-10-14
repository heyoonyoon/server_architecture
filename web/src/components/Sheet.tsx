import type { ReactNode } from 'react'

type SheetProps = {
  title: ReactNode
  note?: ReactNode
  loading: boolean
  onRefresh: () => void
  className?: string
  children: ReactNode
}

export function Sheet({ title, note, loading, onRefresh, className, children }: SheetProps) {
  return (
    <section className={className ? `sheet ${className}` : 'sheet'}>
      <div className="sheet-head">
        <h2>{title}</h2>
        <button type="button" className="sheet-refresh" onClick={onRefresh} disabled={loading}>
          {loading ? '불러오는 중…' : '새로고침'}
        </button>
      </div>
      {note && <p className="sheet-note">{note}</p>}
      {children}
    </section>
  )
}
