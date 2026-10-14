import type { EntityCollection } from '../hooks/useEntityCollection'
import type { Notice } from '../hooks/useNotice'
import { ConfirmButton } from './ConfirmButton'
import { DataTable, type Column } from './DataTable'
import { EntityForm, type FormSchema } from './EntityForm'
import { Panel } from './Panel'

export type EntityView<T, K extends string, D> = {
  itemLabel: string
  idLabel: string
  columns: readonly Column<T>[]
  rowKey: (row: T) => string
  form: FormSchema<K, D>
}

type EntityManagerProps<T, K extends string, D> = {
  view: EntityView<T, K, D>
  collection: EntityCollection<T, D>
  notify: (notice: Notice) => void
}

export function EntityManager<T, K extends string, D>({ view, collection, notify }: EntityManagerProps<T, K, D>) {
  const { itemLabel, idLabel, columns, rowKey, form } = view
  const { items, loading, busy, refresh, add, remove } = collection

  return (
    <Panel title={`${itemLabel} 목록`} loading={loading} onRefresh={() => void refresh()}>
      <DataTable
        columns={columns}
        rows={items}
        rowKey={rowKey}
        loading={loading}
        renderActions={(row) => (
          <ConfirmButton
            className="danger"
            message={`${idLabel} ${rowKey(row)}을(를) 삭제하시겠습니까?`}
            onConfirm={() => void remove(rowKey(row))}
            disabled={busy}
          >
            삭제
          </ConfirmButton>
        )}
      />
      <EntityForm
        title={`${itemLabel} 추가`}
        schema={form}
        busy={busy}
        onSubmit={add}
        onMissingField={(field) => notify({ kind: 'warn', text: `${field.label}을(를) 입력하세요.` })}
      />
    </Panel>
  )
}
