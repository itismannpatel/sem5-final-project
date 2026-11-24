import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react'
import type { Database, User } from '../data/types'
import {
  getCurrentUserId,
  getSnapshot,
  isLoaded,
  startPolling,
  subscribe,
} from '../data/store'
import {
  login as loginRequest,
  logout as logoutRequest,
  register as registerRequest,
  restoreSession,
  type AuthResult,
  type RegisterInput,
} from '../data/api'

interface AppContextValue {
  db: Database
  user: User | null
  /** Real server-side login (email/handle + password). */
  login: (identifier: string, password: string) => Promise<AuthResult>
  /** Create a profile with credentials and sign in. */
  register: (input: RegisterInput) => Promise<AuthResult>
  signOut: () => void
}

const AppContext = createContext<AppContextValue | null>(null)

/**
 * First paint. Shown only until the session probe answers — an anonymous
 * visitor then goes straight to the landing/auth routes, because there is no
 * user data to wait for (and `/api/state` is closed to them).
 */
function BootScreen() {
  return (
    <div className="boot" role="status" aria-live="polite">
      <div className="boot-inner">
        <span className="boot-mark">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
            <path d="M4 8.5h13l-3.2-3.2M20 15.5H7l3.2 3.2" />
          </svg>
        </span>
        <span className="boot-word">
          twofold<span>.</span>
        </span>
        <span className="boot-note">Opening the exchange…</span>
      </div>
    </div>
  )
}

export function AppProvider({ children }: { children: ReactNode }) {
  const db = useSyncExternalStore(subscribe, getSnapshot, getSnapshot)
  const currentUserId = getCurrentUserId()
  const [booted, setBooted] = useState(false)

  // One session probe on boot. No cookie → no state fetch and no poll: the
  // landing and sign-in pages are public, everything else is behind the guard.
  useEffect(() => {
    void restoreSession().finally(() => setBooted(true))
  }, [])

  // Poll (and load the first snapshot) only while signed in. The cleanup stops
  // the interval on sign-out; a 401 mid-poll also clears the session.
  const userId = currentUserId
  useEffect(() => {
    if (!userId) return
    return startPolling()
  }, [userId])

  const user = useMemo(
    () => db.users.find((candidate) => candidate.id === currentUserId) ?? null,
    [db, currentUserId],
  )

  const login = useCallback((identifier: string, password: string) => {
    return loginRequest(identifier, password)
  }, [])

  const register = useCallback((input: RegisterInput) => {
    return registerRequest(input)
  }, [])

  const signOut = useCallback(() => {
    void logoutRequest()
  }, [])

  const value = useMemo<AppContextValue>(
    () => ({ db, user, login, register, signOut }),
    [db, user, login, register, signOut],
  )

  if (!booted) return <BootScreen />
  // A signed-in session has an id as soon as the probe answers, but its rows
  // arrive with the first snapshot. Wait for them: otherwise a hard load of
  // /discover renders the guard with user = null and bounces to /auth.
  if (currentUserId && !isLoaded()) return <BootScreen />

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>
}

export function useApp(): AppContextValue {
  const context = useContext(AppContext)
  if (!context) throw new Error('useApp must be used inside <AppProvider>')
  return context
}

/** Convenience hook for pages that are only rendered behind the auth guard. */
export function useCurrentUser(): User {
  const { user } = useApp()
  if (!user) throw new Error('useCurrentUser called outside an authenticated route')
  return user
}
