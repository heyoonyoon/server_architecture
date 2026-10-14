import { useCallback, useMemo, useState } from 'react'
import { describeError } from '../api/errors'

export type Notice = { kind: 'success' | 'warn' | 'error'; text: string }

export type Notifier = {
  notify: (notice: Notice) => void
  notifyError: (error: unknown) => void
}

export function useNotice() {
  const [notice, setNotice] = useState<Notice | null>(null)
  const dismiss = useCallback(() => setNotice(null), [])
  const notifier = useMemo<Notifier>(
    () => ({
      notify: setNotice,
      notifyError: (error) => setNotice({ kind: 'error', text: describeError(error) }),
    }),
    [],
  )
  return { notice, dismiss, notifier }
}
