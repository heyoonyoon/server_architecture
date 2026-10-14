import { useCallback, useState } from 'react'

export function useBusyAction(onError: (error: unknown) => void) {
  const [busy, setBusy] = useState(false)

  const run = useCallback(
    async <R>(task: () => Promise<R>): Promise<R | undefined> => {
      setBusy(true)
      try {
        return await task()
      } catch (error) {
        onError(error)
        return undefined
      } finally {
        setBusy(false)
      }
    },
    [onError],
  )

  return { busy, run }
}
