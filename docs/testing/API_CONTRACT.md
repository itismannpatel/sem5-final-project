# Twofold — API Contract (executable: `scripts/api-smoke.mjs`)

> **Purpose:** the `/api/*` contract that the **Java backend implements**.
> The Node implementation (`server/api.ts` + `server/queries.ts`) was the reference
> for the port; it was **retired at the Phase 1 parity gate** (deleted — recoverable
> from git history). `scripts/api-smoke.mjs` is the executable form of this contract
> (**95 checks**, updated 25 Nov 2026 when the whole site closed behind auth — see
> [Authentication and actor enforcement](#authentication-and-actor-enforcement-task-2)).
>
> **DEF-010 correction (2026-11-21):** probing the Node server revealed it answered a
> class of *client* mistakes with a bare `500 Internal server error` (schema CHECK /
> UNIQUE / FOREIGN KEY violations escaping to the catch-all), and silently dropped
> invalid skill levels. The Java backend validates before writing instead — where this
> document's tables now say `400`, that is the **contract**, and the Node behaviour was
> the defect (see `DEFECT_LOG.md` DEF-010). Everything else mirrors Node exactly.
>
> **Parity gates:**
> 1. **Baseline (Phase 0):** `node scripts/api-smoke.mjs` against the Node dev server → all green.
> 2. **Migration (Phase 1):** `node scripts/api-smoke.mjs http://localhost:8080` against Spring Boot → all green.
> 3. **Cloud (Phase 3):** `node scripts/api-smoke.mjs https://<deployed-url>` → all green (post-deploy smoke test).
>
> The script takes the base URL as argv: `node scripts/api-smoke.mjs [baseUrl]`
> (default `http://localhost:5173`), mutates data, then **resets the seed at the end**
> (self-cleaning).

## Snapshot semantics

- `GET /api/state` → `200` with the full `Database` document **when a session cookie
  is present**; without one it answers `401 authentication required` (Task 3 — the
  snapshot exposes user rows, so it is no longer a public read):
  `{ version, users[], skills[], sessions[], reviews[], messages[], payments[] }`
  (`payments` added with the sandbox premium feature, D4 — the Node reference had none)
- The only public read is `GET /api/stats` → `200 {stats:{users,skills,sessions,
  completed,reviews,rating}, featured[3 sanitized profiles], quote, heroScore}` —
  counts and featured cards only, never email or credential fields. The landing page
  renders from it.
- `version` is a monotonically increasing integer (seed starts at `1`). **Every
  successful write bumps it** — with three exceptions noted below (no-op paths).
- **Seed timestamps are boot-relative (DEF-011):** `seed.json` records `seededAt`
  (2026-11-21, when the reference `server/seed.ts` last ran); every boot/reset shifts
  all seed dates by whole days so demo data never goes stale — `joinedAt`, session
  slots, `scheduledFor`, `createdAt`, `completedAt`, review/message dates. New rows
  created during a run use the real clock as always.
- Every response is `Content-Type: application/json;charset=utf-8` (Node wrote the
  optional space after `;`, Spring does not — semantically identical) and
  `Cache-Control: no-store`, the latter set centrally for all `/api/` responses.
- **Mutating endpoints return the fresh full snapshot** (one round trip: POST in,
  new state out). The client never fetches after a write.
- Collections are ordered: `users` by name (case-insensitive ASC), `skills` by name ASC,
  `sessions` by `createdAt` DESC, `reviews` by `createdAt` DESC, `messages` by `createdAt`
  ASC, `payments` by `createdAt` ASC (ledger order).
- Hydration rules: each user carries `teaches[]`/`wants[]` (ordered by chosen position);
  each message carries `readBy[]` (always contains the sender).

## Seed invariants (asserted by the smoke suite)

| Collection | Count | Extra assertions |
|---|---|---|
| users | **12** | every user has ≥1 `teaches` and ≥1 `wants` |
| skills | **41** | — |
| sessions | **12** | — |
| reviews | **14** | — |
| messages | **9** | every message has `readBy.length ≥ 1` |
| payments | **0** | empty at seed; only captured purchases append |
| version | number | present |

Known seed ids used by tests: users `u_aarav`, `u_riya`; skills `sk_figma`, `sk_js`, `sk_rust`.

## Endpoints

| Method & path | Success | Validation (400 unless noted) | Notes |
|---|---|---|---|
| `GET /api/state` | 200 snapshot | **session required** (else **401** `authentication required`) | any other method on this path → 405 |
| `GET /api/stats` | 200 public stats | — (no session needed) | `{stats:{users,skills,sessions,completed,reviews,rating}, featured[], quote, heroScore}`; featured cards carry name/handle/branch/year/hue/skills/rating — **never email** |
| `GET /api/matches?userId=` | 200 `MatchResult[]` | **session required** (else **401**); `userId` required (**else 400** `userId is required`); unknown user (**else 400** `no such user: <id>`) | D3 — server-side engine; `mode=all` scores everyone (Discover), default keeps only people who can teach you something (Matches); cached per `version` (recompute after any write); shape: `{user, score, breakdown{learn,teach,affinity,trust}, canLearn[], canTeach[], reciprocal, reasons[]}` |
| `GET /api/premium` | 200 stats | **session required** (else **401**) | D4 — `{payments, revenueMinor, currency, activeSubscribers, byPlan:[{plan,count,revenueMinor}]}`; `byPlan` always lists both catalogue plans (0 when unsold) |
| `GET /api/premium?userId=` | 200 status | **session required** (else **401**); unknown user (**else 400** `no such user: <id>`) | `{userId, active, plan, since, until, payments}` — derived from the ledger; `active` = latest payment inside its plan window; nulls serialize as JSON `null` (stable key set) |
| `GET /api/reminders` | 200 job stats | **session required** (else **401**) | D-multithreading — `{runs, due, written, lastRunAt, windowHours, outbox, recent[]}`; **read-only: never bumps `version`**; `due` = last pass's count, `written` = cumulative lines appended this process |
| `POST /api/users` | **201** snapshot | **requires a session**; `name`, `email` required (trimmed, non-empty); **duplicate email → 400** `email is already registered: <email>` (case-insensitive); **derived handle taken → 400** `handle is already taken: <handle>` | server generates `id = u_<uid>`; `handle` = name lowercased, non-alphanumerics → `.`, leading/trailing dots stripped, fallback `student`; defaults: `availability []`, `format 'Either'`, `verified false`, `joinedAt` now, `hue` random 0–359, `branch` → `'Undeclared'`, `year` → `'1st year'`, `bio` → default blurb |
| `PATCH /api/users/:id` | 200 snapshot | **`:id` must be the session's actor (else 403)**; fields optional; `format` ∈ `Online\|On campus\|Either` (**else 400**); `teaches`/`wants` entries must reference a known skill (**else 400** `no such skill: <id>`) with a valid level (**else 400**) | `teaches`/`wants` are **replaced wholesale** per kind when present (editor owns order/levels); `availability` replaced as array; unknown id + scalar fields → 200 with unchanged row but version still bumps (UPDATE affects 0 rows, bump still runs); unknown id **with** `teaches`/`wants` → 400 `no such user: <id>`; constraints validated only when the row exists (mirrors SQL UPDATE semantics) |
| `POST /api/sessions` | **201** snapshot | **`learnerId` must be the actor (else 403)**; `teacherId`, `learnerId`, `skillId`, `topic` required; **≥1 `proposedSlots`**; teacher/learner/skill must exist (**else 400** `no such teacher\|learner\|skill: <id>`); **`teacherId ≠ learnerId` else 400** | server generates `id = s_<uid>`; `status = 'proposed'`, `createdAt` now; `note` optional |
| `POST /api/sessions/:id/accept` | 200 snapshot | `slot` required; **actor must be a participant (else 403)** | `status = confirmed`, `scheduledFor = slot` |
| `POST /api/sessions/:id/decline` | 200 snapshot | — | `status = declined` |
| `POST /api/sessions/:id/cancel` | 200 snapshot | — | `status = cancelled` |
| `POST /api/sessions/:id/complete` | 200 snapshot | — | `status = completed`, `completedAt = now` |
| `POST /api/sessions/:id/<other>` | **404** | — | `Unknown session action: <action>` |
| `POST /api/reviews` | **201** snapshot | **`fromUserId` must be the actor (else 403)**; `sessionId`, `fromUserId`, `toUserId`, `rating` required; `rating` must satisfy `1 ≤ rating ≤ 5` (checked pre-rounding; `Math.round` applied on store); session and both users must exist (**else 400** `no such session\|user: <id>`) | `text` defaults to `'No comment left.'`; `role` ∈ `taught\|learned` (default `taught`); **duplicate `(sessionId, fromUserId)` → no insert, no version bump, response still 201 with unchanged list** (UNIQUE constraint pre-checked) |
| `POST /api/messages` | **201** snapshot | **`fromUserId` must be the actor (else 403)**; `fromUserId`, `toUserId`, `body` required (trimmed, non-empty); both users must exist (**else 400** `no such user: <id>`) | server generates `id = m_<uid>` (client-sent `id` is ignored); sender added to `readBy` immediately, recipient NOT; `createdAt` now |
| `POST /api/messages/read` | 200 snapshot | **`userId` must be the actor (else 403)**; `userId`, `partnerId` required | marks all `partner → userId` messages read; **version bumped only if ≥1 receipt changed** (idempotent re-run → same version) |
| `POST /api/premium` | **201** snapshot | **`userId` must be the actor (else 403)**; `userId`, `plan`, `cardToken` required (**else 400** `userId is required` / `plan is required` / `cardToken is required`); plan ∈ `pro_monthly` (9900 INR minor/30d) \| `pro_semester` (34900/180d) (**else 400** `unknown plan: <x> (expected pro_monthly\|pro_semester)`); user must exist (**else 400** `no such user: <id>`) | D4 — `SandboxGateway` behind `PaymentGateway`: `tok_decline*` → declined, `tok_insufficient*` → insufficient funds, else captured. **Decline → 402** `{"error":"payment failed: …"}` with **no state change and no version bump** (the checked `PaymentFailedException` is thrown before the write). Success appends `{id=pay_<uid>, userId, plan, amountMinor, currency='INR', cardToken='****<last4>', status='captured', createdAt=now}` and bumps |
| `POST /api/reset` | 200 snapshot | — (**403** `reset is disabled on this deployment` unless `twofold.reset.enabled=true` — default is `false`, `scripts/dev.mjs` opts in locally) | full reseed: counts back to 12/41/12/14/9 + `payments` cleared to `[]`, smoke-test rows removed, edited fields restored, **all accounts and sessions deleted (none recreated)**, version bumped |
| any other `POST`/`PATCH` under `/api/` | **404** | — | `No such endpoint: METHOD /path` |
| non-`POST`/`PATCH` (except the five GETs: `state`, `matches`, `premium`, `reminders`, `stats`) | **405** | — | `Method not allowed` |
| declined sandbox payment | **402** | — | `{"error":"payment failed: …"}` from `PaymentFailedException` — **no version bump, nothing persisted** |
| unhandled server error | **500** | — | `{ error: 'Internal server error' }`, logged server-side; **client keeps its last good snapshot** (must never blank the page) |
| paths not under `/api/` | passed to the host (Vite/SPA) | — | Java serves the built React app here in production |

Malformed JSON body → parsed as `{}` (no 400 by itself; required-field checks then apply).

## Authentication and actor enforcement (Task 2)

Writes require a session cookie; the actor comes from the session, never from the body.
The full contract is in `docs/API.md`; the executable checks are `TC-072a–d`.

| Route | Contract |
|---|---|
| `POST /api/auth/register` | `201` `{user, database}` + `Set-Cookie`; `400` for missing fields / password < 8 / duplicate email / handle taken |
| `POST /api/auth/login` | body `{email\|handle\|identifier, password}`; `200` `{user, database}` + cookie; `401` `invalid email or password` (same message for unknown account and wrong password) |
| `POST /api/auth/logout` | `200` `{ok:true}`, deletes the session row and expires the cookie |
| `GET /api/auth/me` | `200` `{user}` or `401` `not signed in` |
| any other write | `401` `authentication required` without a valid cookie; `403` when the body names another user |
| any other read (`state`, `matches`, `premium`, `reminders`) | `401` `authentication required` without a valid cookie (Task 3); `GET /api/stats` stays public |
| `POST /api/reset` | `403` `reset is disabled on this deployment` unless `twofold.reset.enabled=true`; when enabled it deletes every account and session and recreates none |

Cookie: `twofold_session`, `HttpOnly`, `SameSite=Lax`, `Path=/`, 7-day sliding expiry.
No bearer-token fallback. `401`/`403` happen before any mutation, so `version` never bumps.

## What the smoke suite covers (grouped)

0. **Auth** — anonymous write → 401; anonymous `/api/state` → 401; wrong password →
   401; register a throwaway account → login → `me`; acting
   as another student → 403; logout kills the token; login by handle; second-student
   read receipts (`scripts/api-smoke.mjs` starts with a reset so runs are idempotent).
1. **Baseline** — state responds (with the session); seed counts; hydration; version present.
   Plus the public surface: `/api/stats` shape, featured cards carry no email, and
   "seeded rows still have no password".
2. **Request → accept → complete flow** — create proposed session (201), version bump,
   accept stores `scheduledFor`, complete stamps `completedAt`.
3. **Reviews** — create (201), count grows, duplicate ignored (count unchanged),
   review lands on recipient's profile list.
4. **Messaging** — send (201), sender auto-read, recipient unread, mark-read receipt updates.
5. **User edits** — PATCH bio/teaches/wants replace wholesale.
6. **User create** — 201, count 13, handle derivation (`Smoke Tester` → `smoke.tester`).
7. **Error paths** — empty slots → 400; rating 9 → 400; unknown endpoint → 404.
8. **Premium sandbox (D4)** — stats at zero; purchase captures (masked token, exact
   price, version bump); per-user status flips to active with a future `until`;
   decline → 402 + contract body + **no bump** + not in ledger; insufficient funds → 402;
   unknown plan / missing token → 400 with the exact messages; a user id that is not the
   actor → 403 (acting for another student).
9. **Reminder job (read-only)** — stats shape (`runs`, `windowHours=168`, `outbox`,
   `recent[]`); state version unchanged across the read.
10. **Reset** — counts restored, `payments` cleared, smoke rows cleared, edited bio restored.

## Out of scope (covered later by JUnit/MockMvc, not the smoke suite)

`GET`-method enforcement on non-state endpoints (405), malformed-JSON handling,
ordering guarantees, `PATCH` format validation, unknown-id no-ops, rating rounding of
non-integers (e.g. 4.5 → 5), concurrent write safety, reminder-window boundary rules
and dedupe, payment expiry (BVA around the plan window), and the engine's score
arithmetic (unit-tested against `MatchEngine`).
