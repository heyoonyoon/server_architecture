import { useRemoteData } from './useRemoteData'

export type RemoteList<T> = {
  items: T[]
  loading: boolean
  refresh: () => Promise<void>
}

const EMPTY: never[] = []

export function useRemoteList<T>(load: () => Promise<T[]>, onError: (error: unknown) => void): RemoteList<T> {
  const { data: items, loading, refresh } = useRemoteData<T[]>(load, EMPTY, onError)
  return { items, loading, refresh }
}
