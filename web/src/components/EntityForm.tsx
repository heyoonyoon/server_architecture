import { useState, type FormEvent } from 'react'

export type Field<K extends string> = {
  key: K
  label: string
  required: boolean
  placeholder?: string
}

export type FieldValues<K extends string> = Record<K, string>

export type FormSchema<K extends string, D> = {
  fields: readonly Field<K>[]
  toDraft: (values: FieldValues<K>) => D
}

type EntityFormProps<K extends string, D> = {
  title: string
  schema: FormSchema<K, D>
  busy: boolean
  onSubmit: (draft: D) => Promise<boolean>
  onMissingField: (field: Field<K>) => void
}

function emptyValues<K extends string>(fields: readonly Field<K>[]): FieldValues<K> {
  return Object.fromEntries(fields.map((field) => [field.key, ''])) as FieldValues<K>
}

function trimValues<K extends string>(values: FieldValues<K>): FieldValues<K> {
  return Object.fromEntries(Object.entries<string>(values).map(([key, value]) => [key, value.trim()])) as FieldValues<K>
}

export function EntityForm<K extends string, D>({ title, schema, busy, onSubmit, onMissingField }: EntityFormProps<K, D>) {
  const { fields, toDraft } = schema
  const [values, setValues] = useState(() => emptyValues(fields))

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmed = trimValues(values)
    const missing = fields.find((field) => field.required && !trimmed[field.key])
    if (missing) {
      onMissingField(missing)
      return
    }
    if (await onSubmit(toDraft(trimmed))) {
      setValues(emptyValues(fields))
    }
  }

  return (
    <form className="add-form" onSubmit={(event) => void handleSubmit(event)}>
      <h3>{title}</h3>
      <div className="fields">
        {fields.map((field) => (
          <label key={field.key}>
            <span>
              {field.label}
              {field.required && <em>*</em>}
            </span>
            <input
              value={values[field.key]}
              placeholder={field.placeholder}
              onChange={(event) => setValues({ ...values, [field.key]: event.target.value })}
            />
          </label>
        ))}
      </div>
      <button type="submit" disabled={busy}>
        추가
      </button>
    </form>
  )
}
