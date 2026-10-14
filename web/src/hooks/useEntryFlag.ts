import { useCallback, useState } from 'react'

const STORAGE_PREFIX = 'course-registration.entered.'

function read(key: string): boolean {
  try {
    return sessionStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

function write(key: string, entered: boolean): void {
  try {
    if (entered) {
      sessionStorage.setItem(key, '1')
    } else {
      sessionStorage.removeItem(key)
    }
  } catch {
    return
  }
}

export function useEntryFlag(userId: string) {
  const key = STORAGE_PREFIX + userId
  const [entered, setEntered] = useState(() => read(key))

  const update = useCallback(
    (next: boolean) => {
      write(key, next)
      setEntered(next)
    },
    [key],
  )

  return [entered, update] as const
}
