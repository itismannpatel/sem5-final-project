/**
 * Parity harness for the matching engine (D3): runs the *original* JS engine
 * (src/lib/matching.ts, bundled to an .mjs by esbuild) against the seed
 * snapshot and prints the expected JSON for every user and both modes.
 * Compare with scripts/matches-compare.mjs.
 *
 *   esbuild src/lib/matching.ts --bundle --format=esm --platform=node --outfile=<bundle>
 *   node scripts/matches-expected.mjs [bundlePath] [outFile]
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')
const bundle = process.argv[2] ?? join(here, 'matching.bundle.mjs')
const target = process.argv[3] ?? join(here, 'matches-expected.json')

const { computeMatches, scoreEveryone } = await import(pathToFileURL(bundle).href)
const seed = JSON.parse(readFileSync(join(root, 'backend/src/main/resources/seed.json'), 'utf8'))

// DEF-011: the backend shifts the seed's authored dates to boot-relative
// (SeedClock.java: whole days = today − seededAt's date). MatchResult carries
// the full user, so joinedAt must be shifted identically here or every
// comparison would false-fail on a date the engine never looks at.
function dayShift(seededAt) {
  if (!seededAt) return 0
  const anchor = new Date(seededAt)
  const from = new Date(anchor.getFullYear(), anchor.getMonth(), anchor.getDate())
  const now = new Date()
  const to = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return Math.round((to - from) / 86_400_000)
}
const days = dayShift(seed.seededAt)
if (days !== 0) {
  for (const user of seed.users) {
    user.joinedAt = new Date(Date.parse(user.joinedAt) + days * 86_400_000).toISOString()
  }
}

const out = {}
for (const me of seed.users) {
  out[me.id] = {
    matches: computeMatches(me, seed),
    all: scoreEveryone(me, seed),
  }
}

writeFileSync(target, JSON.stringify(out, null, 2))
console.log(`expected matches written for ${seed.users.length} users → ${target}`)
