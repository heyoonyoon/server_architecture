import type { ReactNode } from 'react'

type ItemListProps = {
  loading: boolean
  empty: string
  children: ReactNode[]
}

export function ItemList({ loading, empty, children }: ItemListProps) {
  if (children.length === 0) {
    return <p className="item-empty">{loading ? '불러오는 중…' : empty}</p>
  }
  return <ul className="item-list">{children}</ul>
}
