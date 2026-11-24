# Twofold — Test Cases

> **Purpose:** the numbered case register for Phase 2 (`agent.md` §4): **76 cases**
> designed with black-box techniques — equivalence partitioning (EP), boundary
> value analysis (BVA) and a decision table — covering valid *and* invalid inputs,
> the session state machine, payments, the matching engine, the reminder job,
> version/lock semantics, error paths and non-functional checks.
>
> **Companions:** strategy, entry/exit criteria and traceability in
> `docs/testing/TEST_PLAN.md`; the executable contract in
> `docs/testing/API_CONTRACT.md`; defects in `docs/testing/DEFECT_LOG.md`.
> The 82-check smoke suite (`scripts/api-smoke.mjs`) is referenced where a case
> overlaps it and is itself registered as TC-072 — these cases **complement** it
> (ordering, headers, concurrency, payments, reminders), they do not restate it.
>
> **Execution note:** `Actual result` and `Status` are recorded during test runs
> in `TEST_SUMMARY.md` (planned vs executed, pass/fail) — that report is written
> after tests actually run, not before.

**Column glossary**

| Column | Meaning |
|---|---|
| ID | `TC-nnn`, unique, never reused |
| Level | `unit` · `integration` (MockMvc) · `E2E` (live server / smoke) · `concurrency` · `load` |
| Precondition | state required before step 1 (server booted = reseeded; `version` read as `v0` where asserted) |
| Steps | numbered request/observations, exact method + path + input |
| Expected result | status, exact message body where the contract pins one, and version delta (`+1` / `unchanged`) |
| Traceability | contract section (`§Snapshot`, `§Endpoints`, `§Seed`) · smoke group (`smoke §n`) · defect (`DEF-nnn`) · design table (`EP-1`…, `DT`) |

**Version assertion convention:** every case that mutates reads `version` from
`GET /api/state` as `v0` first; the expected delta applies to the response body
of the write itself (writes return the fresh snapshot).

---

## 1. Design tables (black-box technique artifacts)

### 1.1 Table EP-1 — Review `rating`: equivalence partitions

Input: `POST /api/reviews` body field `rating` (all other fields valid).

| Class | Partition | Example input | Expected result | Case |
|---|---|---|---|---|
| P1 (valid) | integer in 1–5 | `3` | 201, stored rating `3`, version `+1` | TC-004 |
| P2 (valid, boundary) | `1` or `5` | `1`, `5` | 201, stored as given | TC-005 |
| P3 (valid, coercion) | numeric string | `"4"` | 201, stored `4` (JS `Number()` parity) | TC-004 |
| P4 (valid, non-integer) | decimal inside range | `4.5` | passes pre-rounding check; stored `Math.round(4.5)` = `5` | TC-010 |
| P5 (invalid, below) | `0` | `0` | **400** `sessionId, fromUserId, toUserId and rating are required` (falsy-parity: 0 reports *required*, not *out of range*), version unchanged | TC-006 |
| P6 (invalid, above) | `> 5` | `6`, `9` | **400** `rating must be between 1 and 5`, version unchanged | TC-007 |
| P7 (invalid, non-numeric) | unparsable string | `"excellent"` | **400** `sessionId, fromUserId, toUserId and rating are required` (`NaN` is falsy), version unchanged | TC-008 |
| P8 (invalid, absent) | `null` / key omitted | `null` | **400** `sessionId, fromUserId, toUserId and rating are required`, version unchanged | TC-009 |

### 1.2 Table BVA-1 — Review `rating`: boundary values

| # | Value | Position | Expected result | Case |
|---|---|---|---|---|
| B1 | `0` | min − 1 | 400, *required* message (see EP-1 P5), version unchanged | TC-006 |
| B2 | `1` | min (on) | 201, stored `1` | TC-005 |
| B3 | `2` | min + 1 | 201, stored `2` | TC-004 |
| B4 | `4` | max − 1 | 201, stored `4` | TC-004 || B5 | `5` | max (on) | 201, stored `5` | TC-005 |
| B6 | `6` | max + 1 | 400 `rating must be between 1 and 5`, version unchanged | TC-007 |

### 1.3 Table EP-2 — Session `proposedSlots`: equivalence partitions + boundaries

Input: `POST /api/sessions` body field `proposedSlots` (all other fields valid).

| Class | Partition | Example input | Expected result | Case |
|---|---|---|---|---|
| S1 (valid, 1 entry) | single ISO string | `["2026-12-01T11:00:00.000Z"]` | 201, `proposedSlots.length == 1`, version `+1` | TC-012 |
| S2 (valid, 2 entries) | two ISO strings | 2 distinct instants | 201, both entries preserved **in order** | TC-013 |
| S3 (invalid, 0 entries) | `[]` | `[]` | 400 `at least one proposed slot is required` | TC-014 |
| S4 (invalid, missing) | key omitted | — | 400 `at least one proposed slot is required` | TC-014 |
| S5 (invalid, non-array) | string / number / object / null | `"2026-12-01T11:00:00.000Z"` | 400 same message (`strArray` keeps only arrays → empty) | TC-015 |
| S6 (invalid, no strings inside array) | array of non-strings | `[123]` | 400 same message (non-strings filtered out) | TC-015 |
| S7 (format, no constraint) | malformed ISO **string** | `"tomorrow at five"` | **201**, stored verbatim — the contract validates *count only*, no ISO check (risk R-06; reminder job must skip unparseable instants) | TC-016 |

BVA for count: `0` entries → 400 (B-0), `1` entry → 201 (min, on),
`2` entries → 201 (min + 1).

### 1.4 Table EP-3 — User `format`: equivalence partitions

Input: `PATCH /api/users/:id` body field `format` (existing user row).

| Class | Partition | Example input | Expected result | Case |
|---|---|---|---|---|
| F1 (valid) | exactly `Online` | `"Online"` | 200, value stored, version `+1` | TC-018 |
| F2 (valid) | exactly `On campus` | `"On campus"` | 200, value stored | TC-018 |
| F3 (valid) | exactly `Either` | `"Either"` | 200, value stored | TC-018 |
| F4 (invalid) | other casing / free text | `"Offline"`, `"online"` | 400 `format must be Online, On campus or Either`, version unchanged | TC-019, TC-020 |
| F5 (invalid) | empty / null | `""`, `null` | 400 same message, version unchanged | TC-019 |

Note: constraints are evaluated **only when the row exists** (SQL `UPDATE`
semantics) — unknown id + `format` alone → 200 + bump (see TC-062).

### 1.5 Table EP-4 — Skill entry `level`: equivalence partitions

Input: `PATCH /api/users/:id` body `teaches[]` / `wants[]` entry field `level`.

| Class | Partition | Example input | Expected result | Case |
|---|---|---|---|---|
| L1 (valid) | exactly `Beginner` | `"Beginner"` | 200, stored; version `+1` | TC-021 |
| L2 (valid) | exactly `Intermediate` | `"Intermediate"` | 200, stored | TC-021 |
| L3 (valid) | exactly `Advanced` | `"Advanced"` | 200, stored | TC-021 |
| L4 (invalid) | other value | `"Expert"`, `""` | 400 `level must be Beginner, Intermediate or Advanced` — **rejected, never silently dropped** (DEF-010), version unchanged | TC-022 |
| L5 (invalid, case) | case variant | `"beginner"`, `"ADVANCED"` | 400 same message (set membership is exact) | TC-023 |
| L6 (invalid, FK first) | unknown `skillId` (any level) | `{skillId:"sk_x", level:"Beginner"}` | 400 `no such skill: sk_x` (skill existence checked before level) | TC-024 |

### 1.6 Table DT — Session state machine decision table

Endpoint family `POST /api/sessions/:id/<action>`; `+1` = version bump.

| # | Current state | Action | Input | Expected result | Case |
|---|---|---|---|---|---|
| D1 | `proposed` | `accept` | `slot` = one of `proposedSlots` | 200, `status = confirmed`, `scheduledFor = slot`, `+1` | TC-025 |
| D2 | `proposed` | `decline` | — | 200, `status = declined`, `+1` | TC-026 |
| D3 | `confirmed` | `cancel` | — | 200, `status = cancelled`, `+1` | TC-027 |
| D4 | `confirmed` | `complete` | — | 200, `status = completed`, `completedAt` stamped, `+1` | TC-028 |
| D5 | terminal (`declined`/`cancelled`/`completed`) | any known action | — | 200, status **overwritten** (no status guard — Node parity: the UPDATE has no `WHERE status = …`), `+1` | TC-029 |
| D6 | any | unknown action | e.g. `reschedule` | **404** `Unknown session action: reschedule`, version unchanged | TC-030 |
| D7 | any (checked first) | `accept` | `slot` missing / `""` | 400 `slot is required`, version unchanged | TC-031 |
| D8 | non-existent id | any known action | `s_nonexistent` | **200** with unchanged collection but `+1` — 0-row UPDATE quirk; `accept` still yields 400 if `slot` missing (D7 precedes lookup) | TC-032 |

---

## 2. Baseline & seed

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-001 | Baseline snapshot & seed invariants | E2E | backend booted (fresh reseed) | 1) `GET /api/state` 2) count each collection 3) check hydration & version | 200; `users 12`, `skills 41`, `sessions 12`, `reviews 14`, `messages 9`, `payments 0` (seed `payments: []`); every user has ≥1 `teaches` + ≥1 `wants`; every message `readBy.length ≥ 1`; `version` is a number ≥ 1 | §Seed invariants · smoke §1 |
| TC-002 | Boot-relative seed dates keep future sessions in the future (DEF-011) | E2E | server freshly booted (optionally with system date ≠ seed authoring date 2026-11-21) | 1) `GET /api/state` 2) parse every `proposedSlots[]` and `scheduledFor` of `proposed`/`confirmed` sessions 3) compare each instant to `now` 4) `POST /api/reset`, repeat 2–3 | seed instants authored in the future are still `> now` after boot **and** after reset (whole-day shift `today − seededAt`); instants authored in the past stay in the past; spacing between events preserved; no session shows a stale "in 3 days" as a past date | §Seed invariants · DEF-011 |
| TC-003 | `POST /api/reset` restores full seed incl. `payments` | E2E | state dirtied: ≥1 purchase, edited user, smoke rows present (or run after any suite) | 1) read `v0` 2) `POST /api/reset` | 200; counts back to 12/41/12/14/9; `payments: []`; edited fields restored; smoke rows removed; `version = v0 + 1` | §Endpoints (reset) · smoke §8 |

---

## 3. Reviews — `rating` (EP-1, BVA-1)

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-004 | Valid mid-range rating (incl. numeric string) | integration | existing session + both users; `v0` | 1) `POST /api/reviews` `{"sessionId":…,"fromUserId":…,"toUserId":…,"rating":3}` 2) repeat with `"rating":"4"` | 201 each; stored ratings `3` and `4`; `reviews` grows by 1 each; `version = v0 + 2` | §Endpoints (reviews) · EP-1 P1/P3 · BVA B3/B4 |
| TC-005 | BVA: rating at min (1) and max (5) | integration | fresh session for the author; `v0` | 1) review with `rating:1` 2) new session, review with `rating:5` | both 201; stored `1` and `5`; `version = v0 + 2` | EP-1 P2 · BVA B2/B5 · smoke §3 |
| TC-006 | BVA: rating 0 rejected (falsy parity) | integration | `v0` | 1) `POST /api/reviews` with `rating:0` | 400 `sessionId, fromUserId, toUserId and rating are required`; no row inserted; version unchanged | EP-1 P5 · BVA B1 · DEF-010 |
| TC-007 | BVA: rating 6 rejected | integration | `v0` | 1) `POST /api/reviews` with `rating:6` 2) re-read state | 400 `rating must be between 1 and 5`; `reviews` count unchanged; version unchanged | EP-1 P6 · BVA B6 · smoke §7 (rating 9 variant) |
| TC-008 | Non-numeric rating rejected | integration | `v0` | 1) `POST /api/reviews` with `rating:"excellent"` | 400 `sessionId, fromUserId, toUserId and rating are required` (`NaN` falsy); version unchanged | EP-1 P7 |
| TC-009 | Null / absent rating rejected | integration | `v0` | 1) body without `rating` 2) body with `rating:null` | 400 same required message both times; version unchanged | EP-1 P8 |
| TC-010 | Non-integer rating: pre-round check, round on store | integration | `v0` | 1) `POST /api/reviews` with `rating:4.5` | 201 (check is pre-rounding: 4.5 is inside 1–5); stored rating `5` (`Math.round`); version `+1` | §Endpoints (reviews) · EP-1 P4 |
| TC-011 | Duplicate `(sessionId, fromUserId)` → no insert, no bump | integration | review R1 exists; `v0` | 1) repeat `POST /api/reviews` with same session+author, different `rating`/`text` | 201; `reviews` count unchanged; **version unchanged**; original row retained | §Snapshot (no-op paths) · smoke §3 |

---

## 4. Sessions — creation & `proposedSlots` (EP-2)

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-012 | One proposed slot accepted | integration | known teacher/learner/skill; `v0` | 1) `POST /api/sessions` with `proposedSlots:["2026-12-01T11:00:00.000Z"]` | 201; new row `status=proposed`, single slot preserved; version `+1` | §Endpoints (sessions) · EP-2 S1 |
| TC-013 | Two proposed slots preserved in order | integration | as above; `v0` | 1) create with two distinct ISO slots | 201; `proposedSlots.length == 2`, entry order identical to request | EP-2 S2 · smoke §2 |
| TC-014 | Zero / missing slots rejected | integration | `v0` | 1) create with `proposedSlots:[]` 2) create with the key omitted | 400 `at least one proposed slot is required` both times; version unchanged | EP-2 S3/S4 · smoke §7 |
| TC-015 | Non-array and non-string entries rejected | integration | `v0` | 1) `proposedSlots:"2026-12-01T11:00:00.000Z"` (string) 2) `proposedSlots:[123]` | 400 `at least one proposed slot is required` both times (strArray filter); version unchanged | EP-2 S5/S6 |
| TC-016 | Malformed ISO slot passes through (no format validation) | integration | `v0` | 1) create with `proposedSlots:["tomorrow at five"]` 2) accept with that slot | 201, value stored **verbatim**; accept 200 `confirmed` — contract checks count only (risk R-06: job must skip unparseable instants) | EP-2 S7 · TEST_PLAN R-06 |
| TC-017 | Session-create validation probes (required fields, FK, self-session) | integration | `v0` | 1) body missing `topic` 2) `teacherId:"u_nonexistent"` 3) `teacherId == learnerId` 4) `skillId:"sk_nonexistent"` | 1–4 each **400**, exact messages: `teacherId, learnerId, skillId and topic are required` · `no such teacher: u_nonexistent` · `teacherId and learnerId must be different` · `no such skill: sk_nonexistent`; version unchanged after all four | §Endpoints (sessions) · DEF-010 · smoke §7 |

---

## 5. Users — `format` & skill `level` (EP-3, EP-4)

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-018 | Three valid `format` values accepted | integration | existing user (e.g. `u_aarav`); `v0` | 1) PATCH `format:"Online"` 2) PATCH `"On campus"` 3) PATCH `"Either"` | 200 each; stored value equals request; `version = v0 + 3` | §Endpoints (users) · EP-3 F1–F3 |
| TC-019 | Invalid `format` rejected with exact message | integration | `v0` | 1) PATCH `format:"Offline"` 2) PATCH `format:""` 3) PATCH `format:null` | 400 `format must be Online, On campus or Either` all three; row unchanged; version unchanged | EP-3 F4/F5 · DEF-010 |
| TC-020 | `format` case variant rejected | integration | `v0` | 1) PATCH `format:"online"` 2) PATCH `"EITHER"` | 400 `format must be Online, On campus or Either` (membership is exact); version unchanged | EP-3 F4 |
| TC-021 | Three valid `level` values stored | integration | existing user, known skill ids; `v0` | 1) PATCH `teaches:[{sk_js,"Beginner"},{sk_figma,"Intermediate"},{sk_rust,"Advanced"}]` 2) PATCH `wants` with one `Advanced` entry | 200 each; lists replaced wholesale with levels as sent; `version = v0 + 2` | §Endpoints (users) · EP-4 L1–L3 |
| TC-022 | Invalid `level` rejected, entry not silently dropped | integration | `v0` | 1) PATCH `teaches:[{skillId:"sk_js",level:"Expert"}]` | 400 `level must be Beginner, Intermediate or Advanced`; `teaches` unchanged (the historic Node *silent drop* must not recur); version unchanged | EP-4 L4 · DEF-010 (silent-drop row) |
| TC-023 | `level` case variant rejected | integration | `v0` | 1) PATCH `teaches:[{skillId:"sk_js",level:"beginner"}]` 2) `level:"ADVANCED"` | 400 `level must be Beginner, Intermediate or Advanced` both times; version unchanged | EP-4 L5 |
| TC-024 | Unknown `skillId` in `teaches`/`wants` rejected | integration | `v0` | 1) PATCH `teaches:[{skillId:"sk_nonexistent",level:"Beginner"}]` | 400 `no such skill: sk_nonexistent` (FK check precedes level); version unchanged | EP-4 L6 · DEF-010 |

---

## 6. Session state machine (DT)

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-025 | proposed → accept stores slot | integration | session created (`status=proposed`); `v0` | 1) `POST /api/sessions/{id}/accept` `{"slot": proposedSlots[1]}` | 200; `status=confirmed`, `scheduledFor` equals the sent slot; version `+1` | §Endpoints · DT D1 · smoke §2 |
| TC-026 | proposed → decline | integration | fresh proposed session; `v0` | 1) `POST /{id}/decline` | 200; `status=declined`; version `+1` | DT D2 |
| TC-027 | confirmed → cancel | integration | confirmed session; `v0` | 1) `POST /{id}/cancel` | 200; `status=cancelled`; version `+1` | DT D3 |
| TC-028 | confirmed → complete stamps `completedAt` | integration | confirmed session; `v0` | 1) `POST /{id}/complete` | 200; `status=completed`, `completedAt` present ISO instant; version `+1` | DT D4 · smoke §2 |
| TC-029 | Terminal-state re-action overwrites status | integration | declined session; `v0` | 1) `POST /{id}/accept` with a slot 2) cancel a `completed` session | 200 both times; status overwritten `declined → confirmed`, `completed → cancelled` (no status guard, Node parity); `+1` each | DT D5 |
| TC-030 | Unknown action → 404 exact string | integration | any session; `v0` | 1) `POST /{id}/reschedule` `{}` | 404 `Unknown session action: reschedule`; version unchanged | §Endpoints (404 action) · DT D6 |
| TC-031 | Accept without slot → 400 | integration | proposed session; `v0` | 1) `POST /{id}/accept` `{}` 2) `POST /{id}/accept` `{"slot":""}` | 400 `slot is required` both times; status stays `proposed`; version unchanged | DT D7 |
| TC-032 | Unknown session id → 200-with-bump quirk | integration | `v0` | 1) `POST /api/sessions/s_nonexistent/decline` | 200 full snapshot; no row added/changed; **version `+1`** (0-row UPDATE still bumps) | §Endpoints quirk · DT D8 |

---

## 7. Payments & premium (`POST` / `GET /api/premium`)

Plan catalogue: `pro_monthly` = 9900 minor units INR / 30 days; `pro_semester` = 34900 / 180 days. Sandbox gateway: `tok_decline*` → declined, `tok_insufficient*` → insufficient funds, anything else → success.

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-033 | Successful `pro_monthly` purchase | integration | known user; `payments: []`; `v0` | 1) `POST /api/premium` `{"userId":…,"plan":"pro_monthly","cardToken":"tok_ok"}` | **201** full snapshot; `payments` grows by 1 with shape `{id, userId, plan, amountMinor:9900, currency:"INR", cardToken, status:<captured>, createdAt}`; raw token never persisted verbatim (masked per `Payment`); version `+1` | Premium spec · D4 |
| TC-034 | Successful `pro_semester` purchase | integration | known user; `v0` | 1) purchase `plan:"pro_semester"` | 201; `amountMinor:34900`, `currency:"INR"`; version `+1` | Premium spec · D4 |
| TC-035 | Gateway decline → 402, no state change | integration | `v0`; no prior payments | 1) purchase with `cardToken:"tok_decline_1"` 2) `GET /api/state` | **402** `{"error":"payment failed: …declined…"}`; **version unchanged**; `payments` unchanged (attempt not persisted) | Premium spec (402, no bump) |
| TC-036 | Insufficient funds → 402, no state change | integration | `v0` | 1) purchase with `cardToken:"tok_insufficient_1"` | **402** `{"error":"payment failed: …insufficient funds…"}`; version unchanged; no row | Premium spec (402, no bump) |
| TC-037 | Missing required fields → three exact 400s | integration | `v0` | 1) body without `userId` 2) without `plan` 3) without `cardToken` | 400 each: `userId is required` · `plan is required` · `cardToken is required`; version unchanged after all three | Premium spec (400s) |
| TC-038 | Unknown plan → 400 exact catalogue message | integration | `v0` | 1) `plan:"pro_yearly"` | 400 `unknown plan: pro_yearly (expected pro_monthly\|pro_semester)`; version unchanged | Premium spec (400) |
| TC-039 | Unknown user → 400 | integration | `v0` | 1) `userId:"u_nonexistent"` with valid plan | 400 `no such user: u_nonexistent`; version unchanged | Premium spec (400) |
| TC-040 | Stats correctness before and after a purchase | integration | known user; fresh seed; `v0` | 1) `GET /api/premium` 2) purchase `pro_monthly` 3) `GET /api/premium` 4) `GET /api/premium?userId={user}` | 1) `{payments:0, revenueMinor:0, currency:"INR", activeSubscribers:0, byPlan:[]}` (no positive-count rows) 3) `payments:1`, `revenueMinor:9900`, `activeSubscribers:1`, `byPlan:[{plan:"pro_monthly",count:1,revenueMinor:9900}]` 4) `{userId, active:true, plan:"pro_monthly", since, until, payments:1}` with `until = since + 30d`; stats endpoint never bumps version | Premium spec (stats) |
| TC-041 | BVA: `active` just inside plan window | unit | fabricated payments with `createdAt` offset from now | 1) monthly payment at `now − 30d + 1s` → evaluate active 2) semester payment at `now − 180d + 1s` | `active: true` both; `plan`/`since`/`until` = latest payment, `until = since + duration` | Premium spec (active) · BVA (inside) |
| TC-042 | BVA: `active` just outside plan window | unit | fabricated payments | 1) monthly at `now − 30d − 1s` 2) semester at `now − 180d − 1s` 3) supersede with a newer payment | `active: false` for 1–2 (`until` passed); in 3 the **latest** payment decides — newer payment re-activates | Premium spec (active) · BVA (outside) |
| TC-043 | `payments[]` ordering & reset | integration | two purchases by the same/different users (distinct `createdAt`); `v0` | 1) `GET /api/state` → read `payments` 2) `POST /api/reset` | `payments` ordered by `createdAt` ASC; after reset `payments: []` and seed counts restored; version `+1` on reset | Premium spec (ordering, reset) · §Seed |

---

## 8. Matching engine (`GET /api/matches`)

Engine: `learn 45 / teach 35 / affinity 12 / trust 8`, each signal
`min(1, n/2.0)` (learn, teach) or `min(1, n/3.0)` (affinity) × weight;
`trust = (count>0 ? average/5 : 0.6) × 8`; score = `Math.round` of the
**unrounded** sum; breakdown rounded to 1 dp.

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-044 | Weight arithmetic at full overlap → 100 | unit | constructed `Database`: candidate teaches ≥2 of my wants, wants ≥2 of what I teach, ≥3 shared categories, has reviews averaging 5.0 | 1) `MatchEngine.computeMatches(me, db)` | top result `score == 100`; breakdown exactly `{learn:45.0, teach:35.0, affinity:12.0, trust:8.0}`; weights sum 45+35+12+8 = 100 | §Matching · D3 (engine server-side) |
| TC-045 | Partial-overlap arithmetic & score rounding | unit | candidate: `canLearn=1`, `canTeach=1`, shared categories `=1`, zero reviews | 1) `scoreMatch(me, them, db)` | breakdown `{22.5, 17.5, 4.0, 4.8}`; raw sum `48.8` → `score == 49` (`Math.round` of unrounded sum; caps `min(1,n/2)` and `min(1,n/3)` verified) | §Matching |
| TC-046 | Breakdown 1 dp rounding | unit | candidate with reviews averaging 4.7 (`count ≥ 1`) | 1) compute match 2) inspect breakdown vs raw doubles | `trust` breakdown shown as `7.5` (0.94 × 8 = 7.52 → 1 dp), not `7.52`; score derived from unrounded values; every breakdown component has ≤1 decimal place | §Matching (1 dp) |
| TC-047 | Trust fallback 0.6 when review count is 0 | unit | candidate with **no** reviews; control candidate with reviews | 1) `ratingFor` / score both candidates | zero-review candidate: `Rating(0,0)`, `trustValue = 0.6`, `trust = 4.8` in breakdown; reviewed candidate uses `average/5`; fallback never applied when `count > 0` | §Matching (trust) |
| TC-048 | Empty `canLearn` filtered from default mode, kept in `mode=all` | unit | one candidate sharing no teachable skill | 1) `computeMatches(me, db)` 2) `scoreEveryone(me, db)` | default result **excludes** the zero-overlap candidate (`canLearn` empty filter); `all` mode includes every other user with `canLearn: []`; self never included | §Matching · D3 |
| TC-049 | Ordering: score desc, name tie-break | unit | ≥3 candidates engineered for: score 80, score 80 (second name), score 40 | 1) `computeMatches` 2) read order | scores strictly descending `80, 80, 40`; equal-score pair ordered by **name descending** (mirror of `b.name.localeCompare(a.name)`); stable relative to snapshot order in `scoreEveryone` | §Matching (ordering) |
| TC-050 | Cache: hit at same version, recompute after write | integration | known user; `v0` | 1) `GET /api/matches?userId=…` → A 2) repeat → B 3) `PATCH` a user's `teaches` (version `+1`) 4) GET again → C | A equals B byte-for-byte (same-version cache hit); C reflects the write — **stale results never served** after a bump; no 5xx under repetition | §Matches · MatchService cache |
| TC-051 | Matches error paths | integration | `v0` | 1) `GET /api/matches` (no `userId`) 2) `GET /api/matches?userId=u_nonexistent` | 1) 400 `userId is required` 2) 400 `no such user: u_nonexistent`; version unchanged (all GETs are read-only) | §Matches · D3 |

---

## 9. Reminder job & `GET /api/reminders`

Job rules: `session-soon` for `confirmed` sessions with `scheduledFor` in
`(now, now+window]`; `slot-proposed` per `proposed` slot in that window; dedupe
key `kind|sessionId|instant` within a process run; reminders append as JSONL to
the outbox; endpoint and job are **read-only**.

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-052 | Window boundary — just inside vs just outside | unit | selection logic invoked with injected instants; `windowHours` known | 1) `scheduledFor = now + window − 1s` 2) `scheduledFor = now + window + 1s` 3) `scheduledFor = now` 4) `scheduledFor = now − 1s` (past) | 1) **due** (`session-soon`) 2) not due 3) not due (lower bound is *open*: `> now`) 4) not due (past sessions never remind) | Reminder spec (window `(now, now+window]`) |
| TC-053 | `slot-proposed` per in-window proposed slot | unit | proposed session with 2 slots: one inside window, one outside | 1) run selection | exactly **one** `slot-proposed` reminder for the in-window slot; out-of-window slot not selected; `proposed` sessions never produce `session-soon` | Reminder spec (kinds) |
| TC-054 | Dedupe: same run twice → no duplicate lines | unit | due reminder computed once | 1) run selection/emit twice with the same clock 2) inspect outbox lines | second run adds **zero** lines — key `kind\|sessionId\|instant` already seen in this process run; line count stays 1 per key | Reminder spec (dedupe) |
| TC-055 | Read-only: job and stats endpoint never bump version | integration | `v0` 1) `GET /api/reminders` 2) trigger/run job 3) `GET /api/reminders` 4) `GET /api/state` | | `version` identical at steps 1, 3 and 4 (`v0`); no state collection changed by either | Reminder spec (read-only) · §Snapshot |
| TC-056 | Outbox JSONL line shape | integration | job executed ≥1 time with ≥1 due item | 1) read outbox file 2) split lines 3) `JSON.parse` each | every line a standalone JSON object containing at least `kind` ∈ {`session-soon`, `slot-proposed`}, `sessionId`, `instant` (ISO-8601); one object per line; lines appended in run order; no blank/garbage lines | Reminder spec (JSONL) |
| TC-057 | Job stats endpoint shape | integration | job run at least once; `v0` | 1) `GET /api/reminders` | 200; keys `{runs, due, written, lastRunAt, windowHours, outbox, recent[]}` present with numeric/ISO/array types; `runs ≥ 1`, `lastRunAt` set, `recent` items carry the same fields as outbox lines | Reminder spec (stats) |
| TC-058 | Job stats reflect a triggered run | integration | stats captured before/after invoking job with a known due set | 1) GET stats → S1 2) run job with 2 due items 3) GET stats → S2 | `runs` `+1`; `due == 2`; `written == 2` (or `0` on the deduped re-run); `lastRunAt` advances; version still unchanged | Reminder spec (stats/dedupe) |

---

## 10. Version & locking semantics

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-059 | Every successful write bumps exactly +1; reads never bump | integration | `v0` | one successful call per write endpoint: `POST /api/users`, `PATCH /api/users/{id}`, `POST /api/sessions`, session `accept`, `POST /api/reviews`, `POST /api/messages`, `POST /api/messages/read` (changed), `POST /api/premium`, `POST /api/reset` — then `GET /api/state`, `GET /api/matches`, `GET /api/premium`, `GET /api/reminders` | each write response shows `version = previous + 1` (strictly +1 per call, cumulative `v0 + 9`); every GET leaves `version` unchanged | §Snapshot (every write bumps) |
| TC-060 | Failed writes never bump | integration | `v0` | all 400/402/404 probes from TC-006/007, TC-014, TC-017, TC-019, TC-024, TC-030, TC-031, TC-035–039 run back-to-back without intermediate reset | `version` identical before and after the entire probe sequence; no partial rows | §Snapshot · DEF-010 ("version unchanged on every failure") |
| TC-061 | Idempotent mark-read | integration | unread `partner → user` thread; `v0` | 1) `POST /api/messages/read` 2) repeat same body | 1) 200, receipts updated, `+1` 2) 200, receipts unchanged, **version unchanged** | §Endpoints (messages/read) · smoke §4 |
| TC-062 | Unknown-id PATCH: scalar → 200+bump quirk; skills → 400 | integration | `v0` | 1) `PATCH /api/users/u_nonexistent` `{"bio":"x"}` 2) `PATCH /api/users/u_nonexistent` `{"teaches":[…]}` | 1) 200, no row created, **version `+1`** (0-row UPDATE quirk) 2) 400 `no such user: u_nonexistent`, version unchanged | §Endpoints (PATCH rows) · DEF-010 |
| TC-063 | Concurrent writers: monotonic version, no lost updates | concurrency | JUnit + `ExecutorService`; `v0`; N=8 threads × 25 calls each | 1) all threads `POST /api/messages` simultaneously (latch start) 2) join 3) `GET /api/state` | all 200 responses; final `version == v0 + 200`; `messages` grew by exactly 200 (none lost, none duplicated); no exception/5xx | §Snapshot · StateService RW lock · multithreading deliverable |
| TC-064 | Concurrent readers: version monotonic, snapshots consistent | concurrency | readers sampling while TC-063 writers run | 1) R=4 threads × 50 `GET /api/state` each, interleaved with writers | every response 200; each reader's observed version sequence is **non-decreasing** (equal or +1, never lower); every snapshot self-consistent (`version` numeric, all six collections present and arrays, seed counts intact) | §Snapshot · lock semantics |
| TC-065 | Mixed read/write storm: no torn state | concurrency | mixed workload: readers, writers, one reset | 1) run readers + message writers + a single `POST /api/reset` concurrently 2) final `GET /api/state` | no 5xx; final document valid (counts consistent: 12/41/12 baseline or baseline+writer delta per version); reader samples never expose a document missing a collection | §Snapshot · StateService (snapshot taken under read lock) |

---

## 11. Error paths & protocol (DEF-010 port)

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-066 | Users/sessions 400 probe set with exact messages | integration | `v0` | 1) `POST /api/users` duplicate email (existing address) 2) same email differing only in case 3) name whose derived handle exists 4) `POST /api/sessions` unknown learner 5) unknown skill id | each **400** with exact body: `email is already registered: <email>` (×2, case-insensitive) · `handle is already taken: <handle>` · `no such learner: <id>` · `no such skill: <id>`; version unchanged after all five | §Endpoints · DEF-010 table |
| TC-067 | Reviews/messages 400 probe set | integration | `v0` | 1) `POST /api/reviews` unknown `sessionId` 2) unknown `fromUserId` 3) `POST /api/messages` unknown `toUserId` 4) `POST /api/messages/read` missing `partnerId` 5) `POST /api/messages` empty `body` | each **400**: `no such session: <id>` · `no such user: <id>` · `no such user: <id>` · `userId and partnerId are required` · `fromUserId, toUserId and body are required`; version unchanged | §Endpoints · DEF-010 table |
| TC-068 | Routing matrix: 405 / 404 exact strings | integration | `v0` | 1) `GET /api/users` 2) `PUT /api/state` 3) `DELETE /api/state` 4) `POST /api/nope` 5) `PATCH /api/nope` 6) control: `GET /api/state` | 1–3) **405** `{"error":"Method not allowed"}` 4–5) **404** `{"error":"No such endpoint: POST /api/nope"}` / `… PATCH /api/nope` 6) 200; allowed GETs remain `state|matches|premium|reminders`; version unchanged throughout; non-`/api` paths stay SPA territory | §Endpoints (405/404 rows) · smoke §7 |
| TC-069 | Malformed JSON → `{}` → 400 required-field | integration | `v0` | 1) `POST /api/users` body `{"name":"Broken",` (truncated) 2) body `[1,2]` (non-object) 3) valid JSON `{}` | all parsed as `{}`; each **400** `name and email are required`; **no 500**; version unchanged | §Snapshot (malformed JSON note) · DEF-010 |
| TC-070 | Response headers on success *and* error | integration | `v0` | 1) `GET /api/state` 2) `POST /api/nope` (404) 3) invalid `POST /api/reviews` (400) 4) control: non-API path | all `/api/` responses carry `Content-Type: application/json;charset=utf-8` (no space after `;`, Spring style) **and** `Cache-Control: no-store` — including 4xx/5xx bodies; error bodies are `{"error":…}` JSON | §Snapshot (headers) · NoStoreFilter · smoke (implicit) |

---

## 12. Load & regression

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-071 | N concurrent `/api/state` readers + writers → latency percentiles, no lost updates, monotonic version | load | local backend booted; custom Node 20 harness configured: R=50 readers, W=10 writers, ≥10 s duration or ≥500 requests per role | 1) start readers and writers together 2) record per-request latency & status 3) `POST /api/reset` 4) compute percentiles, final version | all responses 2xx; report produced with **p50 / p95 / p99** for readers and writers (local target p95 ≤ 500 ms); final `version == v0 + (successful writes)` — no lost updates; every reader's version observations non-decreasing; 0 non-2xx, 0 timeouts | TEST_PLAN §3.1 (load) · agent.md Phase 2 load harness |
| TC-072 | Full smoke suite green against the Java backend (regression / parity gate) | E2E | backend on target URL (`localhost:8080`, later cloud URL) | 1) `node scripts/api-smoke.mjs http://localhost:8080` | **95/95 checks pass** (including the auth block, TC-073–076, and the Task 3 gates: anonymous `/api/state` → 401, `/api/stats` sanitization, seeded rows carry no password) (baseline, request→accept→complete, reviews incl. duplicate no-bump, messaging, user edits, user create/handle derivation, error paths, reset); suite self-resets; exit code 0 | API_CONTRACT parity gates 1–3 · smoke §1–§8 |

---

## 13. Authentication (Task 2, added 2026-11-24)

| ID | Title | Level | Precondition | Steps | Expected result | Traceability |
|---|---|---|---|---|---|---|
| TC-073 | Unauthenticated write is rejected (EP: with / without session) | integration | backend booted + reset; no cookie in the request | 1) `POST /api/messages` without cookie 2) read `version` | `401` `{"error":"authentication required"}` with `Cache-Control: no-store`; message count and `version` unchanged | API_CONTRACT §Authentication · smoke §0 · DEF-015 |
| TC-074 | Login: wrong password vs unknown account vs correct (EP + security) | integration | one account created via `/api/auth/register` | 1) login with wrong password 2) login with unknown email 3) login with the registered `handle` + its password 4) `GET /api/auth/me` with the returned cookie | (1) and (2) both `401` `invalid email or password` (same message, no account enumeration); (3) `200` + `Set-Cookie` with `HttpOnly`, `SameSite=Lax`, `Path=/`; (4) `200` with the user and **no** password hash | API_CONTRACT §Authentication · TC-074 |
| TC-075 | Actor enforcement: acting for another student (decision table: actor × body) | integration | signed in as `u_aarav` | 1) message with `fromUserId:u_riya` 2) review with `fromUserId:u_riya` 3) premium with `userId:u_riya` 4) `PATCH /api/users/u_riya` 5) session with `learnerId:u_kabir` 6) complete a session neither participant owns | `403` with the specific message for each; `version` and the target rows unchanged; the same requests with the actor's own id succeed | API_CONTRACT §Actor enforcement · DEF-015 |
| TC-076 | Logout invalidates the token server-side / registration credentials | integration | signed in | 1) logout 2) reuse the old cookie on a write 3) register a new account (password ≥ 8) 4) login with it 5) register again with the same email 6) register with a 3-char password | (2) `401`; (3) `201` + cookie + 13 users; (4) `200`; (5) `400` duplicate email; (6) `400` `password must be at least 8 characters` | API_CONTRACT §Authentication · TC-076 |

---

*Traceability roll-up (contract area → level → cases) is maintained in
`TEST_PLAN.md` §10; new defects found while executing any case go into
`DEFECT_LOG.md` before the fix, and gain a case here (TEST_PLAN §7).*
