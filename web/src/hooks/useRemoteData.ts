import { useCallback, useEffect, useState } from 'react'
import { useBusyAction } from './useBusyAction'

export type RemoteData<T> = {
  data: T
  loading: boolean
  refresh: () => Promise<void>
}

export function useRemoteData<T>(load: () => Promise<T>, initial: T, onError: (error: unknown) => void): RemoteData<T> {
  const [data, setData] = useState<T>(initial)
  const { busy: loading, run } = useBusyAction(onError)

  const refresh = useCallback(async () => {
    await run(async () => setData(await load()))
  }, [run, load])

  useEffect(() => {
    void refresh()
  }, [refresh])

  return { data, loading, refresh }
}
