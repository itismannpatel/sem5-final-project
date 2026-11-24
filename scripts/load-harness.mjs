/**
 * Load harness — the Phase 2 non-functional test (TC-071, TEST_PLAN §3.1).
 *
 *   node scripts/load-harness.mjs [baseUrl] [--readers=50] [--writers=10] [--duration=10]
 *
 * Dependency-free on purpose (same style as api-smoke.mjs): run it on a fresh
 * clone with Node 20 only. It drives concurrent `/api/state` readers and
 * `/api/messages` writers against a *live* backend, measures per-request
 * latency, and checks the invariants that actually matter:
 *
 *   - every response is 2xx (reader 200, writer 201)
 *   - final version == start version + successful writes (no lost updates)
 *   - messages grew by exactly the successful writes (none lost or duplicated)
 *   - each reader's observed version never goes backwards
 *   - p95 stays under the local target (500 ms) — reported, not hidden
 *
 * The suite self-cleans: it POSTs /api/reset at the end, so the next run (or the
 * smoke suite) starts from the documented seed.
 */

const args = process.argv.slice(2)
const BASE = args.find((a) => !a.startsWith('--')) ?? 'http://localhost:8080'

function flag(name, fallback) {
  const hit = args.find((a) => a.startsWith(`--${name}=`))
  if (!hit) return fallback
  const value = Number(hit.split('=')[1])
  return Number.isFinite(value) && value > 0 ? value : fallback
}

const READERS = flag('readers', 50)
const WRITERS = flag('writers', 10)
const DURATION_S = flag('duration', 10)
const WARMUP = flag('warmup', 3)
const P95_TARGET_MS = 500

let failures = 0
const ok = (label, detail = '') => console.log(`  ok   ${label}${detail ? ` — ${detail}` : ''}`)
const bad = (label, detail) => {
  failures += 1
  console.log(`  FAIL ${label} — ${detail}`)
}
const check = (label, condition, detail) => (condition ? ok(label, detail) : bad(label, detail))

/** Session cookie from the harness's own login (Task 2: writes need one). */
let sessionCookie = ''

async function call(method, path, body) {
  const headers = { 'Content-Type': 'application/json' }
  if (sessionCookie) headers.Cookie = sessionCookie
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const raw = typeof res.headers.getSetCookie === 'function'
    ? res.headers.getSetCookie()
    : [res.headers.get('set-cookie')].filter(Boolean)
  for (const cookie of raw) {
    const [pair] = cookie.split(';')
    const [name, value] = pair.split('=')
    if (name === 'twofold_session') sessionCookie = value ? `twofold_session=${value}` : ''
  }
  const json = await res.json().catch(() => null)
  return { status: res.status, json }
}

/** Nearest-rank percentile over an ascending array. */
function percentile(sorted, p) {
  if (sorted.length === 0) return 0
  const rank = Math.ceil((p / 100) * sorted.length)
  return sorted[Math.min(sorted.length - 1, Math.max(0, rank - 1))]
}

function summarise(label, samples) {
  const okLatencies = samples.filter((s) => s.ok).map((s) => s.ms).sort((a, b) => a - b)
  const errors = samples.length - okLatencies.length
  const stats = {
    label,
    requests: samples.length,
    errors,
    p50: percentile(okLatencies, 50),
    p95: percentile(okLatencies, 95),
    p99: percentile(okLatencies, 99),
    min: okLatencies[0] ?? 0,
    max: okLatencies[okLatencies.length - 1] ?? 0,
    mean:
      okLatencies.length === 0
        ? 0
        : okLatencies.reduce((sum, n) => sum + n, 0) / okLatencies.length,
  }
  return stats
}

function printStats(stats) {
  const pad = (n) => n.toFixed(0).padStart(5)
  console.log(
    `  ${stats.label.padEnd(8)} n=${String(stats.requests).padStart(5)}  ` +
      `err=${String(stats.errors).padStart(3)}  ` +
      `p50=${pad(stats.p50)}ms  p95=${pad(stats.p95)}ms  p99=${pad(stats.p99)}ms  ` +
      `min=${pad(stats.min)}ms  max=${pad(stats.max)}ms  mean=${pad(stats.mean)}ms`,
  )
}

console.log(`\nTwofold load harness → ${BASE}`)
console.log(
  `Readers: ${READERS} · Writers: ${WRITERS} · Duration: ${DURATION_S}s · ` +
    `warm-up: ${WARMUP} requests/role\n`,
)

/* ----------------------------------------------------- harness account (T3) */
// Task 3 removed every shared password, so the harness registers its own
// account. It writes to itself: the point is write contention and snapshot
// integrity, not conversation semantics.
const stamp = Date.now()
const HARNESS_PASSWORD = 'load-harness-password'
const registration = await call('POST', '/api/auth/register', {
  name: `Load Harness ${stamp}`,
  email: `harness.${stamp}@example.com`,
  password: HARNESS_PASSWORD,
  branch: 'Load testing',
})
if (registration.status !== 201 || !sessionCookie || !registration.json?.user?.id) {
  console.error(`Cannot register the load harness account (HTTP ${registration.status}).`)
  process.exit(2)
}
const ME = registration.json.user.id
console.log(`Registered harness account ${ME}.\n`)

/* ------------------------------------------------------------- baseline */
const start = await call('GET', '/api/state')
if (start.status !== 200) {
  console.error(`Cannot read baseline state (HTTP ${start.status}). Is the backend running?`)
  process.exit(2)
}
const startVersion = start.json.version
const startMessages = start.json.messages.length

/* -------------------------------------------------------------- warm-up */
for (let i = 0; i < WARMUP; i++) {
  await call('GET', '/api/state')
  await call('POST', '/api/messages', {
    fromUserId: ME,
    toUserId: ME,
    body: `warm-up ${i}`,
  })
}
const afterWarmup = await call('GET', '/api/state')
const warmupVersion = afterWarmup.json.version
const warmupMessages = afterWarmup.json.messages.length
check(
  'warm-up writes each bumped the version',
  warmupVersion === startVersion + WARMUP,
  `v${startVersion} → v${warmupVersion}`,
)

/* ---------------------------------------------------------------- load */
const deadline = Date.now() + DURATION_S * 1000
const readerSamples = Array.from({ length: READERS }, () => [])
const writerSamples = Array.from({ length: WRITERS }, () => [])
const readerVersions = Array.from({ length: READERS }, () => [])
let writerSuccesses = 0
const writerBodies = new Set()
const runTag = Date.now()

async function reader(id) {
  while (Date.now() < deadline) {
    const t0 = performance.now()
    try {
      const res = await call('GET', '/api/state')
      const ms = performance.now() - t0
      readerSamples[id].push({ ms, ok: res.status === 200 })
      if (res.json && typeof res.json.version === 'number') {
        readerVersions[id].push(res.json.version)
      }
    } catch {
      readerSamples[id].push({ ms: performance.now() - t0, ok: false })
    }
  }
}

async function writer(id) {
  let seq = 0
  while (Date.now() < deadline) {
    seq += 1
    const body = `load r${id} #${seq} ${runTag}`
    const t0 = performance.now()
    try {
      const res = await call('POST', '/api/messages', {
        fromUserId: ME,
        toUserId: ME,
        body,
      })
      const ms = performance.now() - t0
      const success = res.status === 201
      writerSamples[id].push({ ms, ok: success })
      if (success) {
        writerSuccesses += 1
        writerBodies.add(body)
      }
    } catch {
      writerSamples[id].push({ ms: performance.now() - t0, ok: false })
    }
  }
}

await Promise.all([
  ...Array.from({ length: READERS }, (_, i) => reader(i)),
  ...Array.from({ length: WRITERS }, (_, i) => writer(i)),
])

const end = await call('GET', '/api/state')
const endVersion = end.json.version
const endMessages = end.json.messages.length

/* -------------------------------------------------------------- report */
const readerStats = summarise('readers', readerSamples.flat())
const writerStats = summarise('writers', writerSamples.flat())
printStats(readerStats)
printStats(writerStats)
console.log('')

/* ------------------------------------------------------------ integrity */
check(
  'no failed reader requests',
  readerStats.errors === 0,
  `${readerStats.errors} of ${readerStats.requests}`,
)
check(
  'no failed writer requests',
  writerStats.errors === 0,
  `${writerStats.errors} of ${writerStats.requests}`,
)
// TC-071's sample rule: a 10 s run *or* 500 requests per role — both are met
// here by the duration, so a slow machine never fails on request count alone.
check(
  'readers produced a valid sample',
  DURATION_S >= 10 || readerStats.requests >= 500,
  `${readerStats.requests} requests in ${DURATION_S}s`,
)
check(
  'writers produced a valid sample',
  DURATION_S >= 10 || writerStats.requests >= 500,
  `${writerStats.requests} requests in ${DURATION_S}s`,
)

check(
  'version arithmetic is exact (no lost updates)',
  endVersion === warmupVersion + writerSuccesses,
  `v${warmupVersion} + ${writerSuccesses} writes = expected v${warmupVersion + writerSuccesses}, got v${endVersion}`,
)
check(
  'message count grew by exactly the successful writes',
  endMessages === warmupMessages + writerSuccesses,
  `${warmupMessages} + ${writerSuccesses} = ${warmupMessages + writerSuccesses}, got ${endMessages}`,
)

check(
  'every successful write is a distinct message body',
  writerBodies.size === writerSuccesses,
  `${writerBodies.size} distinct of ${writerSuccesses}`,
)

const monotonic = readerVersions.every((versions) =>
  versions.every((v, i) => i === 0 || v >= versions[i - 1]),
)
check('every reader observed a non-decreasing version', monotonic)

check(
  'reader p95 within the local target',
  readerStats.p95 <= P95_TARGET_MS,
  `p95 ${readerStats.p95.toFixed(0)} ms (target ≤ ${P95_TARGET_MS} ms)`,
)
check(
  'writer p95 within the local target',
  writerStats.p95 <= P95_TARGET_MS,
  `p95 ${writerStats.p95.toFixed(0)} ms (target ≤ ${P95_TARGET_MS} ms)`,
)

/* ------------------------------------------------------- self-cleanup */
const reset = await call('POST', '/api/reset')
check('reset restores the seed', reset.status === 200, `HTTP ${reset.status}`)
if (reset.json) {
  check(
    'reset returns the documented seed counts',
    reset.json.users.length === 12 &&
      reset.json.sessions.length === 12 &&
      reset.json.messages.length === 9 &&
      reset.json.payments.length === 0,
    `${reset.json.users.length}/${reset.json.sessions.length}/${reset.json.messages.length}/${reset.json.payments.length}`,
  )
}

console.log(
  failures === 0
    ? '\nAll load checks passed.\n'
    : `\n${failures} load check(s) FAILED.\n`,
)
process.exit(failures === 0 ? 0 : 1)
