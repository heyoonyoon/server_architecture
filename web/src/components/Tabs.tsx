export type TabItem<K extends string> = { key: K; label: string }

type TabsProps<K extends string> = {
  items: readonly TabItem<K>[]
  active: K
  onChange: (key: K) => void
}

export function Tabs<K extends string>({ items, active, onChange }: TabsProps<K>) {
  return (
    <nav className="tabs" role="tablist">
      {items.map((item) => (
        <button
          key={item.key}
          type="button"
          role="tab"
          aria-selected={item.key === active}
          onClick={() => onChange(item.key)}
        >
          {item.label}
        </button>
      ))}
    </nav>
  )
}
