type IdListProps = { ids: readonly string[] }

export function IdList({ ids }: IdListProps) {
  return ids.length ? ids.join(', ') : <span className="muted">없음</span>
}
