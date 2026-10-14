import { useSyncExternalStore } from 'react'
import { authStore } from './authStore'

export function useAuth() {
  const { session, signOutReason } = useSyncExternalStore(authStore.subscribe, authStore.getState)
  return { session, signOutReason, signOut: authStore.signOut }
}
