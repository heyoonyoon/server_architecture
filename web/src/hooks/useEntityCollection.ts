import { useCallback } from 'react'
import type { EntityApi } from '../api/entityApi'
import { useBusyAction } from './useBusyAction'
import type { Notifier } from './useNotice'
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
  { notify, notifyError }: Notifier,
): EntityCollection<T, D> {
  const { list, add: addEntity, remove: removeEntity } = source
  const { items, loading, refresh } = useRemoteList(list, notifyError)
  const { busy, run } = useBusyAction(notifyError)

  const add = useCallback(
    async (draft: D) => {
      const added = await run(async () => {
        if (!(await addEntity(draft))) {
          notify({ kind: 'warn', text: messages.duplicate })
          return false
        }
        notify({ kind: 'success', text: messages.added })
        await refresh()
        return true
      })
      return added ?? false
    },
    [run, addEntity, notify, messages, refresh],
  )

  const remove = useCallback(
    async (id: string) => {
      await run(async () => {
        const removed = await removeEntity(id)
        notify(removed ? { kind: 'success', text: messages.deleted } : { kind: 'warn', text: messages.missing })
        await refresh()
      })
    },
    [run, removeEntity, notify, messages, refresh],
  )

  return { items, loading, refresh, busy, add, remove }
}
