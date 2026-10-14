import type { ReactNode } from 'react'

type ItemCardProps = {
  title: ReactNode
  lines: readonly ReactNode[]
  action?: ReactNode
}

export function ItemCard({ title, lines, action }: ItemCardProps) {
  return (
    <li className="item-card">
      <div className="item-body">
        <strong className="item-title">{title}</strong>
        {lines.map((line, index) => (
          <span key={index}>{line}</span>
        ))}
      </div>
      {action && <div className="item-action">{action}</div>}
    </li>
  )
}
