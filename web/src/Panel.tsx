import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { errorMessage } from './api'

export type Notice = { kind: 'success' | 'warn' | 'error'; text: string }

export type Column<T> = { label: string; render: (row: T) => ReactNode }

export type Field = { key: string; label: string; required: boolean; placeholder?: string }

export type PanelProps<T> = {
  title: string
  idLabel: string
  columns: Column<T>[]
  fields: Field[]
  rowId: (row: T) => string
  load: () => Promise<T[]>
  add: (values: Record<string, string>) => Promise<boolean>
  remove: (id: string) => Promise<boolean>
  messages: { added: string; duplicate: string; deleted: string; missing: string }
  onNotice: (notice: Notice) => void
}

function emptyValues(fields: Field[]): Record<string, string> {
  return Object.fromEntries(fields.map((f) => [f.key, '']))
}

export function Panel<T>(props: PanelProps<T>) {
  const { title, idLabel, columns, fields, rowId, load, add, remove, messages, onNotice } = props
  const [rows, setRows] = useState<T[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [values, setValues] = useState(() => emptyValues(fields))

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setRows(await load())
    } catch (e) {
      onNotice({ kind: 'error', text: errorMessage(e) })
    } finally {
      setLoading(false)
    }
  }, [load, onNotice])

  useEffect(() => {
    void refresh()
  }, [refresh])

  async function handleAdd(event: FormEvent) {
    event.preventDefault()
    const missing = fields.find((f) => f.required && !values[f.key].trim())
    if (missing) {
      onNotice({ kind: 'warn', text: `${missing.label}을(를) 입력하세요.` })
      return
    }
    setBusy(true)
    try {
      const trimmed = Object.fromEntries(Object.entries(values).map(([k, v]) => [k, v.trim()]))
      if (await add(trimmed)) {
        onNotice({ kind: 'success', text: messages.added })
        setValues(emptyValues(fields))
        await refresh()
      } else {
        onNotice({ kind: 'warn', text: messages.duplicate })
      }
    } catch (e) {
      onNotice({ kind: 'error', text: errorMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  async function handleDelete(id: string) {
    if (!window.confirm(`${idLabel} ${id}을(를) 삭제하시겠습니까?`)) return
    setBusy(true)
    try {
      if (await remove(id)) {
        onNotice({ kind: 'success', text: messages.deleted })
      } else {
        onNotice({ kind: 'warn', text: messages.missing })
      }
      await refresh()
    } catch (e) {
      onNotice({ kind: 'error', text: errorMessage(e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>{title}</h2>
        <button type="button" className="secondary" onClick={() => void refresh()} disabled={loading}>
          {loading ? '불러오는 중…' : '새로고침'}
        </button>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              {columns.map((c) => (
                <th key={c.label}>{c.label}</th>
              ))}
              <th aria-label="작업" />
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 ? (
              <tr>
                <td className="empty" colSpan={columns.length + 1}>
                  {loading ? '불러오는 중…' : '데이터가 없습니다.'}
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr key={rowId(row)}>
                  {columns.map((c) => (
                    <td key={c.label} data-label={c.label}>
                      {c.render(row)}
                    </td>
                  ))}
                  <td className="actions">
                    <button type="button" className="danger" onClick={() => void handleDelete(rowId(row))} disabled={busy}>
                      삭제
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <form className="add-form" onSubmit={(e) => void handleAdd(e)}>
        <h3>{title.replace(' 목록', '')} 추가</h3>
        <div className="fields">
          {fields.map((f) => (
            <label key={f.key}>
              <span>
                {f.label}
                {f.required && <em>*</em>}
              </span>
              <input
                value={values[f.key]}
                placeholder={f.placeholder}
                onChange={(e) => setValues({ ...values, [f.key]: e.target.value })}
              />
            </label>
          ))}
        </div>
        <button type="submit" disabled={busy}>
          추가
        </button>
      </form>
    </section>
  )
}
