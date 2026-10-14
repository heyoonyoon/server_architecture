import type { ReactNode } from 'react'

type PanelProps = {
  title: string
  loading: boolean
  onRefresh: () => void
  children: ReactNode
}

export function Panel({ title, loading, onRefresh, children }: PanelProps) {
  return (
    <section className="panel">
      <div className="panel-head">
        <h2>{title}</h2>
        <button type="button" className="secondary" onClick={onRefresh} disabled={loading}>
          {loading ? '불러오는 중…' : '새로고침'}
        </button>
      </div>
      {children}
    </section>
  )
}
