import { useCallback, useEffect, useState } from 'react'
import { useBusyAction } from './useBusyAction'

export type RemoteList<T> = {
  items: T[]
  loading: boolean
  refresh: () => Promise<void>
}

export function useRemoteList<T>(load: () => Promise<T[]>, onError: (error: unknown) => void): RemoteList<T> {
  const [items, setItems] = useState<T[]>([])
  const { busy: loading, run } = useBusyAction(onError)

  const refresh = useCallback(async () => {
    await run(async () => setItems(await load()))
  }, [run, load])

  useEffect(() => {
    void refresh()
  }, [refresh])

  return { items, loading, refresh }
}
