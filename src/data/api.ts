import type {
  Database,
  ExchangeSkill,
  Message,
  PremiumStatus,
  RatingSummary,
  Review,
  ReviewRole,
  Session,
  Skill,
  User,
} from './types'
import type { MatchResult } from '../lib/matching'
import {
  applySnapshot,
  getCurrentUserId,
  getSnapshot,
  patch,
  post,
  refresh,
  setSessionUserId,
} from './store'
import { uid } from '../lib/id'

/**
 * The application's data API — the single seam between the UI and storage.
 *
 * Reads are pure functions over the cached snapshot (sync, no loading states).
 * Writes are HTTP calls that return the updated snapshot, so they resolve with
 * the UI already consistent.
 *
 * Components never import `store.ts` directly and never see SQL. Swapping the
 * transport again would mean editing this file and nothing else.
 */

/* ------------------------------------------------------------------ reads */

export function listUsers(db: Database = getSnapshot()): User[] {
  return db.users
}

export function getUser(id: string, db: Database = getSnapshot()): User | undefined {
  return db.users.find((u) => u.id === id)
}

export function getUserByHandle(handle: string, db: Database = getSnapshot()): User | undefined {
  return db.users.find((u) => u.handle.toLowerCase() === handle.toLowerCase())
}

export function getCurrentUser(db: Database = getSnapshot()): User | null {
  const id = getCurrentUserId()
  if (!id) return null
  return db.users.find((u) => u.id === id) ?? null
}

export function listSkills(db: Database = getSnapshot()): Skill[] {
  return db.skills
}

export function getSkill(id: string, db: Database = getSnapshot()): Skill | undefined {
  return db.skills.find((s) => s.id === id)
}

export function listSessionsForUser(userId: string, db: Database = getSnapshot()): Session[] {
  return db.sessions
    .filter((s) => s.teacherId === userId || s.learnerId === userId)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}

export function getSession(id: string, db: Database = getSnapshot()): Session | undefined {
  return db.sessions.find((s) => s.id === id)
}

export function listReviewsForUser(userId: string, db: Database = getSnapshot()): Review[] {
  return db.reviews
    .filter((r) => r.toUserId === userId)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}

export function ratingFor(userId: string, db: Database = getSnapshot()): RatingSummary {
  const reviews = db.reviews.filter((r) => r.toUserId === userId)
  if (reviews.length === 0) return { average: 0, count: 0 }
  const total = reviews.reduce((sum, r) => sum + r.rating, 0)
  return { average: Math.round((total / reviews.length) * 10) / 10, count: reviews.length }
}

export function hasReviewed(
  sessionId: string,
  fromUserId: string,
  db: Database = getSnapshot(),
): boolean {
  return db.reviews.some((r) => r.sessionId === sessionId && r.fromUserId === fromUserId)
}

export interface Thread {
  partnerId: string
  lastMessage: Message
  unreadCount: number
}

export function listThreads(userId: string, db: Database = getSnapshot()): Thread[] {
  const byPartner = new Map<string, Message[]>()
  for (const message of db.messages) {
    if (message.fromUserId !== userId && message.toUserId !== userId) continue
    const partnerId = message.fromUserId === userId ? message.toUserId : message.fromUserId
    const list = byPartner.get(partnerId) ?? []
    list.push(message)
    byPartner.set(partnerId, list)
  }

  return Array.from(byPartner.entries())
    .map(([partnerId, messages]) => {
      const sorted = [...messages].sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      const unreadCount = sorted.filter(
        (m) => m.fromUserId === partnerId && !m.readBy.includes(userId),
      ).length
      return { partnerId, lastMessage: sorted[sorted.length - 1], unreadCount }
    })
    .sort((a, b) => b.lastMessage.createdAt.localeCompare(a.lastMessage.createdAt))
}

export function listMessages(
  userId: string,
  partnerId: string,
  db: Database = getSnapshot(),
): Message[] {
  return db.messages
    .filter(
      (m) =>
        (m.fromUserId === userId && m.toUserId === partnerId) ||
        (m.fromUserId === partnerId && m.toUserId === userId),
    )
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
}

export function unreadTotal(userId: string, db: Database = getSnapshot()): number {
  return db.messages.filter((m) => m.toUserId === userId && !m.readBy.includes(userId)).length
}

/* ----------------------------------------------------------------- writes */

/* ---------------------------------------------------------------- auth (T2) */

export type AuthResult = { ok: true } | { ok: false; error: string }

interface AuthPayload {
  user: User
  database: Database
}

/**
 * Login and registration share one transport: the server returns the signed-in
 * user plus a fresh snapshot and sets the HttpOnly session cookie. We only keep
 * the user id — the token itself is never visible to JavaScript.
 */
async function authRequest(path: string, body: unknown): Promise<AuthResult> {
  try {
    const response = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      credentials: 'same-origin',
    })
    if (response.ok) {
      const payload = (await response.json()) as AuthPayload
      setSessionUserId(payload.user.id)
      applySnapshot(payload.database)
      return { ok: true }
    }
    let message = `Could not sign in (HTTP ${response.status})`
    try {
      const parsed = (await response.json()) as { error?: string }
      if (parsed?.error) message = parsed.error
    } catch {
      /* keep the status-based message */
    }
    return { ok: false, error: message }
  } catch {
    return { ok: false, error: 'Could not reach the server. Is the backend running?' }
  }
}

/** Email or handle, plus password. */
export function login(identifier: string, password: string): Promise<AuthResult> {
  return authRequest('/api/auth/login', { identifier, password })
}

export interface RegisterInput {
  name: string
  email: string
  password: string
  branch: string
  year: string
  bio: string
}

/** Creates the profile and its credentials, and signs the new student in. */
export function register(input: RegisterInput): Promise<AuthResult> {
  return authRequest('/api/auth/register', input)
}

/** Ends the session server-side (the cookie is cleared too). */
export async function logout(): Promise<void> {
  try {
    await fetch('/api/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
    })
  } catch {
    /* signing out locally still has to work */
  }
  setSessionUserId(null)
}

/** Boot probe: is this browser holding a valid session cookie? */
export async function restoreSession(): Promise<boolean> {
  try {
    const response = await fetch('/api/auth/me', {
      cache: 'no-store',
      credentials: 'same-origin',
    })
    if (!response.ok) {
      setSessionUserId(null)
      return false
    }
    const payload = (await response.json()) as { user: User }
    setSessionUserId(payload.user.id)
    return true
  } catch {
    return false
  }
}
export interface UserPatch {
  name?: string
  branch?: string
  year?: string
  bio?: string
  format?: User['format']
  availability?: string[]
  teaches?: User['teaches']
  wants?: User['wants']
}

export function updateUser(id: string, patch_: UserPatch): Promise<void> {
  return patch(`/api/users/${id}`, patch_)
}

export interface NewSessionInput {
  teacherId: string
  learnerId: string
  skillId: string
  topic: string
  proposedSlots: string[]
  note?: string
}

export function requestSession(input: NewSessionInput): Promise<void> {
  return post('/api/sessions', input)
}

export function acceptSession(id: string, slot: string): Promise<void> {
  return post(`/api/sessions/${id}/accept`, { slot })
}

export function declineSession(id: string): Promise<void> {
  return post(`/api/sessions/${id}/decline`)
}

export function cancelSession(id: string): Promise<void> {
  return post(`/api/sessions/${id}/cancel`)
}

export function completeSession(id: string): Promise<void> {
  return post(`/api/sessions/${id}/complete`)
}

export interface NewReviewInput {
  sessionId: string
  fromUserId: string
  toUserId: string
  rating: number
  text: string
  role: ReviewRole
}

export function addReview(input: NewReviewInput): Promise<void> {
  return post('/api/reviews', {
    ...input,
    id: `r_${uid()}`,
  })
}

export function sendMessage(fromUserId: string, toUserId: string, body: string): Promise<void> {
  const trimmed = body.trim()
  if (!trimmed) return Promise.resolve()
  return post('/api/messages', { fromUserId, toUserId, body: trimmed, id: `m_${uid()}` })
}

export function markThreadRead(userId: string, partnerId: string): Promise<void> {
  return post('/api/messages/read', { userId, partnerId })
}

/* ------------------------------------------------------------ public stats */

/** A featured profile as the public endpoint exposes it — no email, no id leaks beyond the profile. */
export interface PublicStatsUser {
  id: string
  name: string
  handle: string
  branch: string
  year: string
  hue: number
  verified: boolean
  teaches: ExchangeSkill[]
  wants: ExchangeSkill[]
  rating: RatingSummary
}

/** Everything the landing page may know before anyone has signed in. */
export interface PublicStats {
  users: number
  skills: number
  sessions: number
  completed: number
  reviews: number
  rating: RatingSummary
  featured: PublicStatsUser[]
  quote: {
    text: string
    rating: number
    fromName: string
    toName: string
    createdAt: string
  } | null
  heroScore: number
}

/**
 * The anonymous landing data. `/api/stats` is the only read that does not need
 * a session, because it returns aggregates and sanitized cards — never the user
 * rows `/api/state` would expose.
 */
export async function fetchPublicStats(): Promise<PublicStats | null> {
  try {
    const response = await fetch('/api/stats', { cache: 'no-store' })
    if (!response.ok) return null
    return (await response.json()) as PublicStats
  } catch {
    // The landing page renders a static skeleton rather than crashing.
    return null
  }
}

/* -------------------------------------------------------------- matches (D3) */

/**
 * Server-ranked matches: `one` keeps only people who can teach you something,
 * `all` scores everyone (Discover). Returns `null` on any failure so the caller
 * can fall back to the local engine instead of showing an empty screen.
 */
export async function fetchMatches(
  userId: string,
  mode: 'one' | 'all',
): Promise<MatchResult[] | null> {
  const query = mode === 'all' ? '&mode=all' : ''
  try {
    const response = await fetch(
      `/api/matches?userId=${encodeURIComponent(userId)}${query}`,
      { cache: 'no-store', credentials: 'same-origin' },
    )
    if (!response.ok) return null
    return (await response.json()) as MatchResult[]
  } catch {
    return null
  }
}

/* -------------------------------------------------------------- premium (D4) */

/** One user's Twofold Pro standing, derived server-side from the ledger. */
export async function fetchPremiumStatus(userId: string): Promise<PremiumStatus | null> {
  try {
    const response = await fetch(`/api/premium?userId=${encodeURIComponent(userId)}`, {
      cache: 'no-store',
      credentials: 'same-origin',
    })
    if (!response.ok) return null
    return (await response.json()) as PremiumStatus
  } catch {
    return null
  }
}

/**
 * Sandbox purchase. Resolves with the server's `{"error": …}` message on a
 * declined card (HTTP 402) so the UI can show exactly what happened, and
 * refreshes the snapshot after a capture.
 */
export async function purchasePremium(
  userId: string,
  plan: string,
  cardToken: string,
): Promise<{ ok: true } | { ok: false; error: string }> {
  try {
    const response = await fetch('/api/premium', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ userId, plan, cardToken }),
      credentials: 'same-origin',
    })
    if (response.ok) {
      await refresh()
      return { ok: true }
    }
    let message = `Payment failed (HTTP ${response.status})`
    try {
      const body = (await response.json()) as { error?: string }
      if (body?.error) message = body.error
    } catch {
      /* keep the status-based message */
    }
    return { ok: false, error: message }
  } catch {
    return { ok: false, error: 'Payment failed: could not reach the server.' }
  }
}
