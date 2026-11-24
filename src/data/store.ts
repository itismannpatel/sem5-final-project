import type { Database } from './types'

/**
 * Client-side snapshot bus.
 *
 * Data lives in the backend's SQLite database and arrives over HTTP. Sync
 * model: the server stamps every write with a monotonic `version`. The client
 * polls `GET /api/state` once a second and only re-renders when that version
 * actually moves, so an idle tab does no work beyond one tiny comparison.
 * Mutations return the fresh snapshot directly, so your own writes paint
 * immediately rather than waiting for the next tick.
 *
 * Authentication (Task 2): the session is an opaque token in an HttpOnly cookie
 * the browser sends automatically — this file never sees it. The only thing we
 * keep is the *id* of the signed-in student, resolved from `GET /api/auth/me`
 * and from login/register responses. A 401 anywhere clears it, which drops the
 * route guard back to `/auth`.
 */

const POLL_MS = 1000

const EMPTY: Database = {
  version: 0,
  users: [],
  skills: [],
  sessions: [],
  reviews: [],
  messages: [],
  payments: [],
}

let snapshot: Database = EMPTY
let loaded = false
let polling = false
let pollTimer: number | null = null
let authUserId: string | null = null
const listeners = new Set<() => void>()

function emit() {
  listeners.forEach((listener) => listener())
}

function apply(next: Database) {
  // Same version means nothing we render from has changed — skip the re-render.
  if (loaded && next.version === snapshot.version) return
  snapshot = next
  loaded = true
  emit()
}

/* ------------------------------------------------------ external store API */

export function getSnapshot(): Database {
  return snapshot
}

export function isLoaded(): boolean {
  return loaded
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Apply a snapshot returned by a write or an auth response. */
export function applySnapshot(next: Database): void {
  snapshot = next
  loaded = true
  emit()
}

/* ------------------------------------------------------------- transport */

export async function refresh(): Promise<void> {
  // `/api/state` now requires a session. Anonymous visitors hold no user rows
  // at all, so a poll here would just collect 401s.
  if (!authUserId) return
  try {
    const response = await fetch('/api/state', {
      cache: 'no-store',
      credentials: 'same-origin',
    })
    if (response.status === 401) {
      // The session expired or was signed out elsewhere: drop it quietly and
      // stop polling. The route guard takes the user to /auth.
      stopPolling()
      setSessionUserId(null)
      return
    }
    if (!response.ok) return
    apply((await response.json()) as Database)
  } catch {
    // Server briefly unavailable (first boot, HMR restart). Keep last state.
  }
}

async function send(method: 'POST' | 'PATCH', path: string, body: unknown): Promise<void> {
  try {
    const response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body ?? {}),
      credentials: 'same-origin',
    })
    if (response.ok) {
      apply((await response.json()) as Database)
      return
    }
    if (response.status === 401) {
      // The session expired or was signed out in another tab.
      setSessionUserId(null)
    }
  } catch {
    /* fall through to a refetch */
  }
  await refresh()
}

/** POST a mutation; the server replies with the updated snapshot. */
export function post(path: string, body?: unknown): Promise<void> {
  return send('POST', path, body)
}

/** PATCH a mutation; the server replies with the updated snapshot. */
export function patch(path: string, body?: unknown): Promise<void> {
  return send('PATCH', path, body)
}

/**
 * Start the 1s poll. Returns a stop function; safe to call twice, and a no-op
 * while signed out (there is nothing to poll for). Pauses while the tab is
 * hidden so a backgrounded tab costs nothing.
 */
export function startPolling(): () => void {
  if (!authUserId || polling) return () => stopPolling()
  polling = true

  const tick = () => {
    if (document.visibilityState === 'visible') void refresh()
  }

  void refresh()
  pollTimer = window.setInterval(tick, POLL_MS)

  return () => stopPolling()
}

/** Idempotent: used by 401 handling as well as by the component cleanup. */
export function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
  polling = false
}

/* ------------------------------------------------------------------- auth */

/**
 * The signed-in student's id — the server-side session is the actual authority.
 * Set from `/api/auth/me` on boot and from the login/register response; cleared
 * by logout or any 401.
 */
export function getCurrentUserId(): string | null {
  return authUserId
}

export function setSessionUserId(id: string | null): void {
  if (authUserId === id) return
  authUserId = id
  // The signed-in id lives outside the snapshot, so the data itself has not
  // changed. Nudge the snapshot identity anyway: `useSyncExternalStore` bails
  // out when the snapshot is `Object.is`-equal, and without this a fresh
  // sign-in would never re-render the app.
  if (loaded) snapshot = { ...snapshot }
  emit()
}
