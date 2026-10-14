import type { ReactNode } from 'react'

export type Column<T> = { label: string; render: (row: T) => ReactNode }

type DataTableProps<T> = {
  columns: readonly Column<T>[]
  rows: readonly T[]
  rowKey: (row: T) => string
  loading: boolean
  renderActions?: (row: T) => ReactNode
}

export function DataTable<T>({ columns, rows, rowKey, loading, renderActions }: DataTableProps<T>) {
  const columnCount = columns.length + (renderActions ? 1 : 0)
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.label}>{column.label}</th>
            ))}
            {renderActions && <th aria-label="작업" />}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td className="empty" colSpan={columnCount}>
                {loading ? '불러오는 중…' : '데이터가 없습니다.'}
              </td>
            </tr>
          ) : (
            rows.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((column) => (
                  <td key={column.label} data-label={column.label}>
                    {column.render(row)}
                  </td>
                ))}
                {renderActions && <td className="actions">{renderActions(row)}</td>}
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  )
}
