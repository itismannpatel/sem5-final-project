/**
 * Compares the Java backend's GET /api/matches output against the JS engine's
 * expected output (scripts/matches-expected.mjs) for every user and both modes.
 *
 *   node scripts/matches-compare.mjs [expectedFile] [baseUrl]
 *
 * Values are compared structurally (parsed JSON), so Java's `45.0` and JS's
 * `45` are equal — but every number, string, and array element must match.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const expectedFile = process.argv[2] ?? join(here, 'matches-expected.json')
const base = process.argv[3] ?? 'http://localhost:8080'

const expected = JSON.parse(readFileSync(expectedFile, 'utf8'))
const mismatches = []

function deepEq(a, b, path) {
  const aArr = Array.isArray(a)
  const bArr = Array.isArray(b)
  if (aArr || bArr) {
    if (!aArr || !bArr) return mismatches.push(`${path}: ${JSON.stringify(a)} ≠ ${JSON.stringify(b)}`)
    if (a.length !== b.length) return mismatches.push(`${path}.length: ${a.length} ≠ ${b.length}`)
    for (let i = 0; i < a.length; i++) deepEq(a[i], b[i], `${path}[${i}]`)
    return
  }
  const aObj = a && typeof a === 'object'
  const bObj = b && typeof b === 'object'
  if (aObj || bObj) {
    if (!aObj || !bObj) return mismatches.push(`${path}: ${JSON.stringify(a)} ≠ ${JSON.stringify(b)}`)
    const ka = Object.keys(a).sort()
    const kb = Object.keys(b).sort()
    if (ka.join(',') !== kb.join(',')) {
      return mismatches.push(`${path} keys: [${ka}] ≠ [${kb}]`)
    }
    for (const k of ka) deepEq(a[k], b[k], `${path}.${k}`)
    return
  }
  if (a !== b) {
    mismatches.push(`${path}: ${JSON.stringify(a)} ≠ ${JSON.stringify(b)}`)
  }
}

// Task 3 gates /api/matches behind a session, so the script registers a
// throwaway account. It is the 13th student and the expectation file covers the
// 12-student seed, so it is filtered out of the `mode=all` arrays before the
// comparison — the seed rows themselves are still compared byte for byte.
const stamp = `${process.pid}.${Date.now()}`
const registration = await fetch(`${base}/api/auth/register`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    name: `Matches Compare ${stamp}`,
    email: `compare.${stamp}@example.com`,
    password: 'matches-compare-password',
  }),
})
if (registration.status !== 201) {
  console.error(`\nCannot register the comparison account (HTTP ${registration.status}).\n`)
  process.exit(2)
}
const cookies = typeof registration.headers.getSetCookie === 'function'
  ? registration.headers.getSetCookie()
  : [registration.headers.get('set-cookie')].filter(Boolean)
const cookie = cookies.map((value) => value.split(';')[0]).join('; ')
const harnessUser = (await registration.json()).user?.id
if (!cookie || !harnessUser) {
  console.error('\nRegistration returned no session cookie / user.\n')
  process.exit(2)
}

let requests = 0
for (const [userId, modes] of Object.entries(expected)) {
  for (const [label, query] of [['matches', ''], ['all', '&mode=all']]) {
    const res = await fetch(`${base}/api/matches?userId=${userId}${query}`, {
      headers: { Cookie: cookie },
    })
    if (res.status !== 200) {
      mismatches.push(`${userId}/${label}: HTTP ${res.status} ${await res.text()}`)
      continue
    }
    const raw = await res.json()
    const actual = Array.isArray(raw)
      ? raw.filter((match) => match.user?.id !== harnessUser)
      : raw
    requests++
    deepEq(modes[label], actual, `${userId}/${label}`)
  }
}

console.log(`\n${requests} endpoint calls compared against the JS engine.`)
if (mismatches.length === 0) {
  console.log('PARITY OK — Java output matches src/lib/matching.ts exactly.\n')
  process.exit(0)
}
console.log(`${mismatches.length} mismatch(es):`)
for (const m of mismatches.slice(0, 40)) console.log(`  ${m}`)
if (mismatches.length > 40) console.log(`  … and ${mismatches.length - 40} more`)
process.exit(1)
