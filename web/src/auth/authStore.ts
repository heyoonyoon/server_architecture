import { clearStoredSession, readStoredSession, writeStoredSession, type Session } from './session'

export type AuthState = {
  session: Session | null
  signOutReason: string | null
}

type Listener = () => void

let state: AuthState = { session: readStoredSession(), signOutReason: null }
const listeners = new Set<Listener>()

function setState(next: AuthState) {
  state = next
  listeners.forEach((listener) => listener())
}

export const authStore = {
  getState: (): AuthState => state,
  subscribe(listener: Listener): () => void {
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  },
  signIn(session: Session) {
    writeStoredSession(session)
    setState({ session, signOutReason: null })
  },
  signOut() {
    clearStoredSession()
    setState({ session: null, signOutReason: null })
  },
  expire(token: string, reason: string) {
    if (state.session?.token !== token) return
    clearStoredSession()
    setState({ session: null, signOutReason: reason })
  },
}
