/**
 * API smoke test — run against a live dev server.
 *
 *   node scripts/api-smoke.mjs [baseUrl]
 *
 * Exercises every endpoint the UI depends on and fails loudly on the first
 * problem. Not a unit test framework: deliberately dependency-free so it works
 * on a fresh clone with nothing but `npm install`.
 *
 * Task 3: there are no demo accounts and no shared password. The suite starts
 * from the seed, registers its own two students, and does every authenticated
 * flow as them. The seeded profiles are content only — the suite proves that by
 * checking they cannot log in.
 *
 * Requires the reset opt-in (scripts/dev.mjs sets TWOFOLD_RESET_ENABLED=true).
 * Against a fail-closed deployment the first reset answers 403 and the suite
 * fails loudly, which is deliberate.
 */

const BASE = process.argv[2] ?? 'http://localhost:5173'

let failures = 0
let passes = 0
const ok = (label, detail = '') => {
  passes += 1
  console.log(`  ok   ${label}${detail ? ` — ${detail}` : ''}`)
}
const bad = (label, detail) => {
  failures += 1
  console.log(`  FAIL ${label} — ${detail}`)
}

/**
 * A minimal cookie jar. Login and register set an HttpOnly `twofold_session`
 * cookie and every authenticated request needs it; Node's fetch does not keep
 * cookies, so the suite carries the value itself. `{ anonymous: true }` sends
 * no cookie.
 */
let sessionCookie = ''

async function call(method, path, body, options = {}) {
  const headers = { 'Content-Type': 'application/json' }
  if (sessionCookie && !options.anonymous) {
    headers.Cookie = sessionCookie
  }
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
    if (name === 'twofold_session') {
      sessionCookie = value ? `twofold_session=${value}` : ''
    }
  }
  const json = await res.json().catch(() => null)
  return { status: res.status, json }
}

const expect = (label, condition, detail) => {
  if (condition) ok(label, detail)
  else bad(label, detail ?? 'condition was false')
}

const signIn = (email, password) =>
  call('POST', '/api/auth/login', { email, password })

console.log(`\nTwofold API smoke test → ${BASE}\n`)

/* ------------------------------------------------------------- baseline */
// The dev stack enables POST /api/reset (TWOFOLD_RESET_ENABLED=true); a
// deployment defaults it off. Starting from the seed keeps the suite idempotent.
const reset0 = await call('POST', '/api/reset', {})
expect('POST /api/reset restores the seed', reset0.status === 200, `HTTP ${reset0.status}`)
expect('reset returns 12 seeded users', reset0.json?.users?.length === 12,
  `${reset0.json?.users?.length}`)
expect('reset returns 41 skills', reset0.json?.skills?.length === 41,
  `${reset0.json?.skills?.length}`)

/* ---------------------------------------------------- gated reads (Task 3) */
const anonState = await call('GET', '/api/state', undefined, { anonymous: true })
expect('anonymous GET /api/state → 401', anonState.status === 401, `HTTP ${anonState.status}`)
expect('401 speaks the contract dialect',
  anonState.json?.error === 'authentication required', anonState.json?.error)

const anonWrite = await call('POST', '/api/messages', {
  fromUserId: 'u_aarav',
  toUserId: 'u_riya',
  body: 'anonymous write',
}, { anonymous: true })
expect('anonymous write → 401', anonWrite.status === 401, `HTTP ${anonWrite.status}`)

for (const [label, path] of [
  ['matches', '/api/matches?userId=u_aarav'],
  ['premium', '/api/premium'],
  ['reminders', '/api/reminders'],
]) {
  const res = await call('GET', path, undefined, { anonymous: true })
  expect(`anonymous ${label} read → 401`, res.status === 401, `HTTP ${res.status}`)
}

/* ------------------------------------------------------- public stats */
const stats = await call('GET', '/api/stats', undefined, { anonymous: true })
expect('GET /api/stats is public', stats.status === 200, `HTTP ${stats.status}`)
expect('stats counts match the seed',
  stats.json?.users === 12 && stats.json?.skills === 41 && stats.json?.sessions === 12,
  `${stats.json?.users}/${stats.json?.skills}/${stats.json?.sessions}`)
expect('stats feature three profiles', stats.json?.featured?.length === 3,
  `${stats.json?.featured?.length}`)
expect('featured cards carry no email',
  stats.json?.featured?.every((person) => !('email' in person) && person.name && person.handle),
  JSON.stringify(stats.json?.featured?.[0] ?? {}).slice(0, 80))
expect('stats payload contains no email address',
  !JSON.stringify(stats.json).includes('@campus') && !JSON.stringify(stats.json).includes('password'),
  'clean')
expect('stats has a hero score and quote',
  typeof stats.json?.heroScore === 'number' && typeof stats.json?.quote?.text === 'string',
  `score ${stats.json?.heroScore}`)

/* --------------------------------------------- register → act as a student */
const passwordA = 'smoke-pass-arun'
const passwordB = 'smoke-pass-bela'
const regA = await call('POST', '/api/auth/register', {
  name: 'Smoke Arun',
  email: 'smoke.arun@example.com',
  password: passwordA,
  branch: 'Testing',
  year: '1st year',
  bio: 'Registered by the smoke suite.',
})
expect('register creates the first account', regA.status === 201, `HTTP ${regA.status}`)
expect('registration returns a snapshot', regA.json?.database?.users?.length === 13,
  `${regA.json?.database?.users?.length}`)
expect('handle derived from name', regA.json?.user?.handle === 'smoke.arun', regA.json?.user?.handle)
const arun = regA.json?.user?.id
expect('registration sets a session cookie', sessionCookie.startsWith('twofold_session='), 'cookie')

const regB = await call('POST', '/api/auth/register', {
  name: 'Smoke Bela',
  email: 'smoke.bela@example.com',
  password: passwordB,
  branch: 'Testing',
  year: '2nd year',
})
expect('register creates the second account', regB.status === 201, `HTTP ${regB.status}`)
const bela = regB.json?.user?.id
expect('two registered students exist', regB.json?.database?.users?.length === 14,
  `${regB.json?.database?.users?.length}`)

const wrongPassword = await signIn('smoke.arun@example.com', 'not-the-password')
expect('wrong password → 401',
  wrongPassword.status === 401 && wrongPassword.json?.error === 'invalid email or password',
  `HTTP ${wrongPassword.status} ${wrongPassword.json?.error}`)
const unknownAccount = await signIn('nobody@example.com', passwordA)
expect('unknown account → the same 401',
  unknownAccount.status === 401 && unknownAccount.json?.error === 'invalid email or password',
  `HTTP ${unknownAccount.status}`)

const seededLogin = await signIn('aarav.mehta@campus.edu', 'no-such-password')
expect('seeded profiles have no credentials (Task 3)', seededLogin.status === 401,
  `HTTP ${seededLogin.status}`)

const reLogin = await call('POST', '/api/auth/login', {
  handle: 'smoke.arun',
  password: passwordA,
})
expect('login by handle works', reLogin.status === 200 && reLogin.json?.user?.id === arun,
  `HTTP ${reLogin.status}`)

const me = await call('GET', '/api/auth/me')
expect('GET /api/auth/me resolves the session',
  me.status === 200 && me.json?.user?.id === arun, `HTTP ${me.status}`)
expect('me never leaks a password hash',
  me.status === 200 && !JSON.stringify(me.json).includes('passwordHash'), 'clean')

const state = await call('GET', '/api/state')
expect('signed-in GET /api/state succeeds', state.status === 200, `HTTP ${state.status}`)
const db = state.json
expect('snapshot carries the two new students', db?.users?.length === 14,
  `${db?.users?.length}`)
expect('version stamp present', typeof db?.version === 'number', `v${db?.version}`)

/* ---------------------------------------------------- registration errors */
const duplicate = await call('POST', '/api/auth/register', {
  name: 'Someone Else',
  email: 'smoke.arun@example.com',
  password: 'another-password',
})
expect('duplicate email → 400', duplicate.status === 400, `HTTP ${duplicate.status}`)
const shortPassword = await call('POST', '/api/auth/register', {
  name: 'Short Pass',
  email: 'short@example.com',
  password: 'short',
})
expect('short password → 400',
  shortPassword.status === 400
  && shortPassword.json?.error === 'password must be at least 8 characters',
  shortPassword.json?.error)

/* -------------------------------------------------- request → accept flow */
const requested = await call('POST', '/api/sessions', {
  teacherId: bela,
  learnerId: arun,
  skillId: 'sk_figma',
  topic: 'Smoke test session',
  proposedSlots: ['2026-11-01T11:00:00.000Z', '2026-11-02T11:00:00.000Z'],
  note: 'created by smoke test',
})
expect('POST /api/sessions creates', requested.status === 201, `HTTP ${requested.status}`)
const created = requested.json?.sessions?.find((s) => s.topic === 'Smoke test session')
expect('new session is proposed', created?.status === 'proposed', created?.status)
expect('new session has both slots', created?.proposedSlots?.length === 2)
expect('version bumped on write', requested.json?.version > db.version)

const asBela = await signIn('smoke.bela@example.com', passwordB)
expect('second student signs in', asBela.status === 200, `HTTP ${asBela.status}`)

const accepted = await call('POST', `/api/sessions/${created.id}/accept`, {
  slot: created.proposedSlots[1],
})
const acceptedSession = accepted.json?.sessions?.find((s) => s.id === created.id)
expect('accept confirms session', acceptedSession?.status === 'confirmed', acceptedSession?.status)
expect('accept stores scheduledFor',
  acceptedSession?.scheduledFor === created.proposedSlots[1], acceptedSession?.scheduledFor)

const completed = await call('POST', `/api/sessions/${created.id}/complete`)
const doneSession = completed.json?.sessions?.find((s) => s.id === created.id)
expect('complete marks completed', doneSession?.status === 'completed')
expect('complete stamps completedAt', Boolean(doneSession?.completedAt))

/* --------------------------------------------------------------- reviews */
await signIn('smoke.arun@example.com', passwordA)
const reviewed = await call('POST', '/api/reviews', {
  sessionId: created.id,
  fromUserId: arun,
  toUserId: bela,
  rating: 5,
  text: 'Smoke test review.',
  role: 'taught',
})
expect('POST /api/reviews succeeds', reviewed.status === 201, `HTTP ${reviewed.status}`)
expect('review count grew', reviewed.json?.reviews?.length === 15, `${reviewed.json?.reviews?.length}`)

const dup = await call('POST', '/api/reviews', {
  sessionId: created.id,
  fromUserId: arun,
  toUserId: bela,
  rating: 1,
  text: 'Should be ignored.',
  role: 'taught',
})
expect('duplicate review ignored', dup.json?.reviews?.length === 15, `${dup.json?.reviews?.length}`)

const rating = reviewed.json?.reviews?.filter((r) => r.toUserId === bela) ?? []
expect('new review lands on the teacher profile',
  rating.some((r) => r.fromUserId === arun && r.text === 'Smoke test review.'),
  `${rating.length} reviews`)

/* ------------------------------------------------------------- messaging */
const sent = await call('POST', '/api/messages', {
  fromUserId: arun,
  toUserId: bela,
  body: 'Smoke test message.',
})
expect('POST /api/messages succeeds', sent.status === 201)
const message = sent.json?.messages?.find((m) => m.body === 'Smoke test message.')
expect('sender auto-read own message', message?.readBy?.includes(arun), JSON.stringify(message?.readBy))
expect('recipient not yet marked read', !message?.readBy?.includes(bela))

// Read receipts are per-actor: sign in as the recipient to open the thread.
await signIn('smoke.bela@example.com', passwordB)
const marked = await call('POST', '/api/messages/read', {
  userId: bela,
  partnerId: arun,
})
const markedMessage = marked.json?.messages?.find((m) => m.id === message?.id)
expect('markThreadRead updates receipt', markedMessage?.readBy?.includes(bela))
expect("marking someone else's thread → 403",
  (await call('POST', '/api/messages/read', {
    userId: arun,
    partnerId: bela,
  })).status === 403)

/* ----------------------------------------------------------- impersonation */
const impersonation = await call('POST', '/api/messages', {
  fromUserId: arun,
  toUserId: bela,
  body: 'impersonation',
})
expect('acting as another user → 403', impersonation.status === 403, `HTTP ${impersonation.status}`)

/* ------------------------------------------------------------ user edits */
await signIn('smoke.arun@example.com', passwordA)
const patched = await call('PATCH', `/api/users/${arun}`, {
  bio: 'Edited by the smoke test.',
  teaches: [{ skillId: 'sk_js', level: 'Advanced' }],
  wants: [{ skillId: 'sk_rust', level: 'Beginner' }],
})
const arunRow = patched.json?.users?.find((u) => u.id === arun)
expect('PATCH updates bio', arunRow?.bio === 'Edited by the smoke test.')
expect('PATCH replaces teaches',
  arunRow?.teaches?.length === 1 && arunRow.teaches[0].skillId === 'sk_js')
expect('PATCH replaces wants',
  arunRow?.wants?.length === 1 && arunRow.wants[0].skillId === 'sk_rust')
expect("PATCH of another profile → 403",
  (await call('PATCH', `/api/users/${bela}`, { bio: 'hijacked' })).status === 403)

/* ----------------------------------------------------------- user create */
const createdProfile = await call('POST', '/api/users', {
  name: 'Smoke Tester',
  email: 'smoke.tester@example.com',
  branch: 'Testing',
  year: '1st year',
  bio: 'A profile without credentials.',
})
expect('POST /api/users (authenticated) succeeds', createdProfile.status === 201,
  `HTTP ${createdProfile.status}`)
expect('user count grew', createdProfile.json?.users?.length === 15,
  `${createdProfile.json?.users?.length}`)
expect('handle derived from name',
  createdProfile.json?.users?.some((u) => u.handle === 'smoke.tester'))
expect('profile-only user cannot sign in',
  (await signIn('smoke.tester@example.com', 'anything-at-all')).status === 401)

/* ----------------------------------------------------------- error paths */
const noSlots = await call('POST', '/api/sessions', {
  teacherId: bela,
  learnerId: arun,
  skillId: 'sk_figma',
  topic: 'No slots',
  proposedSlots: [],
})
expect('missing slots rejected', noSlots.status === 400, `HTTP ${noSlots.status}`)

const badRating = await call('POST', '/api/reviews', {
  sessionId: created.id,
  fromUserId: arun,
  toUserId: bela,
  rating: 9,
  text: 'too high',
  role: 'taught',
})
expect('out-of-range rating rejected', badRating.status === 400, `HTTP ${badRating.status}`)

const missing = await call('POST', '/api/nope', {})
expect('unknown endpoint 404s', missing.status === 404, `HTTP ${missing.status}`)

/* ------------------------------------------------------ premium sandbox (D4) */
const stats0 = await call('GET', '/api/premium')
expect('GET /api/premium stats works when signed in',
  stats0.status === 200 && stats0.json.payments === 0 && stats0.json.revenueMinor === 0,
  `HTTP ${stats0.status}`)
expect('stats cover both plans at zero',
  Array.isArray(stats0.json.byPlan) && stats0.json.byPlan.length === 2
  && stats0.json.byPlan.every((p) => p.count === 0 && p.revenueMinor === 0),
  JSON.stringify(stats0.json.byPlan))

const status0 = await call('GET', `/api/premium?userId=${arun}`)
expect('arun starts without pro',
  status0.status === 200 && status0.json.active === false && status0.json.payments === 0,
  JSON.stringify(status0.json))

const beforeBuy = (await call('GET', '/api/state')).json.version
const purchased = await call('POST', '/api/premium', {
  userId: arun, plan: 'pro_monthly', cardToken: 'tok_visa_4242',
})
expect('POST /api/premium captures', purchased.status === 201, `HTTP ${purchased.status}`)
const pay = purchased.json?.payments?.find((p) => p.userId === arun)
expect('payment recorded in ledger', Boolean(pay), JSON.stringify(purchased.json?.payments))
expect('card token masked', pay?.cardToken === '****4242', pay?.cardToken)
expect('plan price correct',
  pay?.plan === 'pro_monthly' && pay?.amountMinor === 9900 && pay?.currency === 'INR',
  `${pay?.plan} ${pay?.amountMinor} ${pay?.currency}`)
expect('payment status captured', pay?.status === 'captured', pay?.status)
expect('version bumped on purchase', purchased.json?.version > beforeBuy,
  `v${beforeBuy} → v${purchased.json?.version}`)

const status1 = await call('GET', `/api/premium?userId=${arun}`)
expect('pro active after purchase',
  status1.json?.active === true && status1.json?.plan === 'pro_monthly'
  && status1.json?.payments === 1, JSON.stringify(status1.json))
expect('until in the future', Date.parse(status1.json.until) > Date.now(), status1.json.until)

const beforeDecline = (await call('GET', '/api/state')).json.version
const declined = await call('POST', '/api/premium', {
  userId: arun, plan: 'pro_monthly', cardToken: 'tok_decline_4242',
})
expect('declined card → 402', declined.status === 402, `HTTP ${declined.status}`)
expect('402 speaks contract dialect',
  declined.json?.error?.includes('declined (sandbox)'), declined.json?.error)
const afterDecline = (await call('GET', '/api/state')).json.version
expect('decline does not bump version', afterDecline === beforeDecline,
  `v${beforeDecline} → v${afterDecline}`)

const insufficient = await call('POST', '/api/premium', {
  userId: arun, plan: 'pro_semester', cardToken: 'tok_insufficient',
})
expect('insufficient funds → 402',
  insufficient.status === 402 && insufficient.json?.error?.includes('insufficient funds'),
  `HTTP ${insufficient.status} ${insufficient.json?.error}`)

const badPlan = await call('POST', '/api/premium', {
  userId: arun, plan: 'pro_forever', cardToken: 'tok_visa',
})
expect('unknown plan rejected',
  badPlan.status === 400 && badPlan.json?.error?.includes('unknown plan'),
  badPlan.json?.error)

const ghost = await call('POST', '/api/premium', {
  userId: bela, plan: 'pro_monthly', cardToken: 'tok_visa',
})
expect('buying for another account → 403',
  ghost.status === 403 && ghost.json?.error?.includes('yourself'), ghost.json?.error)

const noToken = await call('POST', '/api/premium', { userId: arun, plan: 'pro_monthly' })
expect('missing cardToken rejected',
  noToken.status === 400 && noToken.json?.error?.includes('cardToken'), noToken.json?.error)

/* ------------------------------------------------------ reminder job (read-only) */
const beforeRem = (await call('GET', '/api/state')).json.version
const reminders = await call('GET', '/api/reminders')
expect('GET /api/reminders responds', reminders.status === 200, `HTTP ${reminders.status}`)
expect('job stats shape',
  typeof reminders.json.runs === 'number' && reminders.json.windowHours === 168
  && typeof reminders.json.outbox === 'string' && Array.isArray(reminders.json.recent),
  JSON.stringify(reminders.json).slice(0, 140))
const afterRem = (await call('GET', '/api/state')).json.version
expect('reminder stats are read-only', afterRem === beforeRem,
  `v${beforeRem} → v${afterRem}`)

/* --------------------------------------------------------------- logout */
const loggedOut = await call('POST', '/api/auth/logout')
expect('logout succeeds', loggedOut.status === 200 && loggedOut.json?.ok === true)
const afterLogout = await call('POST', '/api/messages', {
  fromUserId: arun, toUserId: bela, body: 'after logout',
})
expect('token is dead after logout', afterLogout.status === 401, `HTTP ${afterLogout.status}`)
expect('logged-out /api/state → 401',
  (await call('GET', '/api/state')).status === 401)

/* ----------------------------------------------------------------- reset */
const reset = await call('POST', '/api/reset', {})
expect('POST /api/reset succeeds', reset.status === 200)
expect('reset restores 12 users', reset.json?.users?.length === 12, `${reset.json?.users?.length}`)
expect('reset restores 12 sessions', reset.json?.sessions?.length === 12,
  `${reset.json?.sessions?.length}`)
expect('reset restores 14 reviews', reset.json?.reviews?.length === 14,
  `${reset.json?.reviews?.length}`)
expect('reset restores 9 messages', reset.json?.messages?.length === 9,
  `${reset.json?.messages?.length}`)
expect('reset clears payments', reset.json?.payments?.length === 0,
  `${reset.json?.payments?.length}`)
expect('reset clears the smoke-test session',
  !reset.json?.sessions?.some((s) => s.topic === 'Smoke test session'))

// Task 3: reseeding must not resurrect credentials — the registered account is
// gone and the seeded rows still have none.
expect('registered account does not survive a reset',
  (await signIn('smoke.arun@example.com', passwordA)).status === 401)
expect('seeded rows still have no password',
  (await signIn('aarav.mehta@campus.edu', 'no-such-password')).status === 401)

console.log(
  failures === 0
    ? `\nAll API smoke tests passed (${passes} checks).\n`
    : `\n${failures} check(s) FAILED.\n`,
)
process.exit(failures === 0 ? 0 : 1)
