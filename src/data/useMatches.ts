import { useEffect, useState } from 'react'
import type { MatchResult } from '../lib/matching'
import { fetchMatches } from './api'

/**
 * Server-side match scores (D3), with the snapshot version as the cache key.
 *
 * The engine lives in the backend so the UI and the API cannot disagree, but a
 * page still paints instantly from the local snapshot: this returns `null`
 * until the current version's response lands, and callers fall back to the
 * local engine for that first frame. A failed request keeps returning `null`,
 * so an offline tab still renders ranked matches from `src/lib/matching.ts`.
 */
export function useServerMatches(
  userId: string,
  mode: 'one' | 'all',
  version: number,
): MatchResult[] | null {
  const [cached, setCached] = useState<{ version: number; results: MatchResult[] } | null>(null)

  useEffect(() => {
    let alive = true
    void fetchMatches(userId, mode).then((results) => {
      if (alive && results) setCached({ version, results })
    })
    return () => {
      alive = false
    }
  }, [userId, mode, version])

  return cached && cached.version === version ? cached.results : null
}
