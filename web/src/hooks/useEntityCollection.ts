import { useCallback } from 'react'
import { alertError } from '../api/errors'
import type { EntityApi } from '../api/entityApi'
import { useBusyAction } from './useBusyAction'
import { useRemoteList, type RemoteList } from './useRemoteList'

export type EntityMessages = {
  added: string
  duplicate: string
  deleted: string
  missing: string
}

export type EntityCollection<T, D> = RemoteList<T> & {
  busy: boolean
  add: (draft: D) => Promise<boolean>
  remove: (id: string) => Promise<void>
}

export function useEntityCollection<T, D>(
  source: EntityApi<T, D>,
  messages: EntityMessages,
): EntityCollection<T, D> {
  const { list, add: addEntity, remove: removeEntity } = source
  const { items, loading, refresh } = useRemoteList(list, alertError)
  const { busy, run } = useBusyAction(alertError)

  const add = useCallback(
    async (draft: D) => {
      const added = await run(async () => {
        if (!(await addEntity(draft))) {
          window.alert(messages.duplicate)
          return false
        }
        window.alert(messages.added)
        await refresh()
        return true
      })
      return added ?? false
    },
    [run, addEntity, messages, refresh],
  )

  const remove = useCallback(
    async (id: string) => {
      await run(async () => {
        const removed = await removeEntity(id)
        window.alert(removed ? messages.deleted : messages.missing)
        await refresh()
      })
    },
    [run, removeEntity, messages, refresh],
  )

  return { items, loading, refresh, busy, add, remove }
}
