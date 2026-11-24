import { useEffect, useState } from 'react'
import { fetchPublicStats, type PublicStats } from './api'

/**
 * The anonymous landing payload (`GET /api/stats`) — counts, sanitized featured
 * cards, one quote and the hero score. `null` means "still loading or
 * unreachable"; callers render a static fallback rather than crashing.
 */
export function usePublicStats(): PublicStats | null {
  const [stats, setStats] = useState<PublicStats | null>(null)

  useEffect(() => {
    let alive = true
    void fetchPublicStats().then((next) => {
      if (alive) setStats(next)
    })
    return () => {
      alive = false
    }
  }, [])

  return stats
}
