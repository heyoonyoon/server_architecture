import { useSyncExternalStore } from 'react'
import { authStore } from './authStore'

export function useAuth() {
  const { session } = useSyncExternalStore(authStore.subscribe, authStore.getState)
  return { session, signOut: authStore.signOut }
}
