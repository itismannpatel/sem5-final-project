# Twofold — Test Plan

> **Purpose:** the strategy for verifying the Java backend against
> `docs/testing/API_CONTRACT.md` — scope, levels, tools, environments, roles,
> entry/exit criteria, data handling, defect workflow, risks, schedule and
> traceability. This is the Phase 2 planning deliverable named in `agent.md` §4;
> its companion is `docs/testing/TEST_CASES.md` (the case register), and results
> are consolidated later in `TEST_SUMMARY.md`.
>
> **Status:** living document — updated as the contract grows (premium/payments,
> reminders) and as defects are logged in `docs/testing/DEFECT_LOG.md`.
> The executable contract (`scripts/api-smoke.mjs`, 42 checks) and this plan are
> deliberately independent: the smoke suite is treated as a *test asset* here,
> referenced by traceability, not rewritten.

---

## 1. Scope & objectives

### 1.1 Objectives, mapped to the assignment's testing requirements

The Software Testing brief (`agent.md` §4, row 1) asks for a test plan &
strategy, 30+ documented test cases designed with black-box techniques, JUnit
unit tests with a coverage report (statement/branch), integration & system tests
of main flows including error paths, non-functional tests (load, security,
usability), a defect log, regression evidence, a post-deployment smoke test and
a test summary report. How each requirement is satisfied:

| # | Assignment requirement | Deliverable / activity | Where |
|---|---|---|---|
| O1 | Test plan & strategy (scope, levels, tools, env, roles, entry/exit) | this document | `docs/testing/TEST_PLAN.md` |
| O2 | 30+ test cases (ID, description, input, expected, actual, status), EP/BVA/decision tables, valid + invalid inputs | 72 numbered cases + 6 technique tables | `docs/testing/TEST_CASES.md` |
| O3 | JUnit unit tests + coverage report + statement/branch reflection | JUnit 5 suites per service; JaCoCo report | `backend/src/test/`, `backend/target/site/jacoco/` |
| O4 | Integration & system tests of main flows incl. error paths | MockMvc suite (ported smoke checks + DEF-010 probe sets + premium/reminders) | `backend/src/test/` |
| O5 | Non-functional: ≥1 performance/load test | N-reader/N-writer harness with latency percentiles | TC-071, §4 |
| O6 | Security check | security spot-check write-up (separate Phase 2 document) | §1.2 |
| O7 | Usability check with real users | usability checklist executed by the Frontend Lead (separate Phase 2 document) | §1.2 |
| O8 | Defect log (ID, severity, priority, repro, status, fix) | live defect log, DEF-001… | `docs/testing/DEFECT_LOG.md` |
| O9 | Regression evidence | smoke re-run at every gate; case re-runs after fixes, recorded in the summary | §3, §9 |
| O10 | Post-deployment smoke test | `node scripts/api-smoke.mjs https://<cloud-url>` at Phase 3 | API_CONTRACT parity gate 3 |
| O11 | Test summary report (planned vs executed, pass/fail, coverage, defects, lessons) | written **after** execution | `TEST_SUMMARY.md` (not created until tests run) |

### 1.2 In scope

| Area | What is tested |
|---|---|
| Snapshot contract | `GET /api/state` shape, collection ordering, hydration, monotonic `version`, `Content-Type` / `Cache-Control` headers |
| Users | create (handle derivation, defaults, duplicate email/handle), PATCH (scalar fields, `format`, wholesale `teaches`/`wants` replacement, unknown-id quirks) |
| Sessions | create validation, `proposedSlots` partitions, full state machine (accept/decline/cancel/complete), unknown action/id |
| Reviews | required fields, `rating` EP/BVA, rounding, duplicate `(sessionId, fromUserId)` no-op |
| Messaging | send, auto-read semantics, idempotent mark-read |
| Matching (`GET /api/matches`) | weight arithmetic (45/35/12/8), 1 dp breakdown, trust fallback, filtering, ordering, version-keyed cache, error paths |
| Premium/payments (`POST|GET /api/premium`) | plan catalogue, sandbox gateway outcomes (success/decline/insufficient), 402 semantics with **no state change**, stats & per-user `active` expiry BVA, `payments[]` ordering and reset |
| Reminders (`GET /api/reminders` + job) | window boundaries `(now, now+window]`, dedupe key, outbox JSONL, job stats, read-only (never bumps `version`) |
| Cross-cutting | version bump rules for every write (and every failed write), `ReentrantReadWriteLock` semantics under concurrent readers/writers, boot reseed, `POST /api/reset`, seed date relativity (DEF-011) |
| Error paths | the DEF-010 probe set with exact messages, routing matrix (405/404 strings), malformed-JSON → `{}` → 400 |
| Non-functional | local load test (latency percentiles, lost-update check), security spot checks, usability checklist with real users |

### 1.3 Out of scope

| Item | Rationale | Covered instead by |
|---|---|---|
| The retired Node backend (`server/`, deleted) | retired at the Phase 1 parity gate; recoverable from git history | historical evidence in DEFECT_LOG |
| Real payment processing | D4: sandbox only — no keys, no money, no gateway network calls | `SandboxGateway` unit tests (in scope) |
| `PostgresStore` behaviour | not implemented yet (D2 cloud phase); local store is `JsonFileStore` | Phase 3 verification |
| Frontend unit tests | no JS test framework was added; the UI reads only through `src/data/api.ts` | API-level E2E (smoke) + usability checklist |
| Visual/CSS regression, cross-browser matrix | judged manually; historical defects DEF-002/DEF-003 were visual | usability checklist |
| Absolute latency SLAs on cloud | Render free tier sleeps and has cold starts; numbers would not be meaningful | local percentiles + post-deploy smoke only |
| Load-testing the React bundle / browser perf | not an assignment requirement | — |

---

## 2. Test levels & strategy

Strategy: a **contract-first test pyramid**. The API contract is executable
(`api-smoke.mjs`), so every level below is written *against the contract*, not
against the implementation's current behaviour. Tests are woven in from day one
(`agent.md` §4, Phase 2 row), not deferred to a testing phase.

| Level | Technique | Tool | What it covers | Executor | When |
|---|---|---|---|---|---|
| **Unit** | white-box, pure-function & branch testing | JUnit 5 (Jupiter), via `mvnw test` | `MatchEngine` arithmetic/ordering, `Js` coercion helpers (`str`, `strArray`, `jsNumber`, `handleFor`), `SeedClock` day-shift, plan-expiry predicate, reminder window/dedupe logic, `SandboxGateway` token rules, validation branches | Java Lead | alongside every service change |
| **Integration** | HTTP-level black-box through the full Spring stack | MockMvc (`spring-boot-starter-webmvc-test`) | every `/api/*` endpoint: happy paths, EP/BVA partitions, DEF-010 probe sets, exact error strings, headers, version bump/no-bump, reset, premium stats, reminder stats | Backend & DB Lead | alongside endpoint work |
| **Concurrency** | multi-threaded invariant testing | JUnit + `ExecutorService` / `CountDownLatch` | `StateService` read/write lock: N concurrent writers → version arithmetic & no lost updates; concurrent readers → monotonic version, consistent snapshots; `MatchService` cache under parallel hits | Java Lead | after `StateService`/`MatchService` stabilise |
| **Load** | black-box stress with measured latencies | custom Node 20 harness (dependency-free, sibling of `scripts/api-smoke.mjs`) | N concurrent `/api/state` readers + writers → p50/p95/p99, error count, no lost updates, version monotonicity | QA Lead | before each gate |
| **E2E / system** | end-to-end contract regression against a live server | `scripts/api-smoke.mjs` (42 checks) | the full request → accept → complete flow, reviews, messaging, user edits/create, three contract 400s, reset — one self-cleaning run | QA Lead | every gate (§3), post-deploy |
| **Manual / exploratory** | scripted spot checks with real users | security spot-check list; usability checklist | injection probes, header/secret review, error-message clarity, real-user flows | QA + Frontend Lead | Phase 2 |

**Regression rule:** any defect fixed gets (a) a new or extended case in
`TEST_CASES.md`, (b) a re-run of the affected level plus the smoke suite, and
(c) the re-run evidence recorded in `TEST_SUMMARY.md`. The smoke suite is the
permanent regression net because it is runnable on a fresh clone with only
`npm install`.

---

## 3. Entry & exit criteria

### 3.1 Per-level criteria

| Level | Entry criteria | Exit criteria |
|---|---|---|
| Unit | class under test compiles; expected behaviour pinned in `API_CONTRACT.md` | all unit tests green on 3 consecutive runs; JaCoCo **statement ≥ 70 %** and **branch ≥ 55 %** for `com.twofold.backend` (excluding generated); every validation branch in `ApiController` exercised by ≥1 case |
| Integration | backend boots locally (`npm run dev` → healthy on `:8080`); smoke baseline green | MockMvc suite green; every row of the contract's endpoint table has ≥1 passing case; all DEF-010 probe sets return the exact documented messages with `version` unchanged |
| Concurrency | `StateService` / `MatchService` implemented and unit-green | suite green **10 consecutive runs**; final `version` equals initial + number of successful writes in every run |
| Load | local backend stable; harness configured (N readers, M writers, duration) | report produced with p50/p95/p99; 0 non-2xx responses; version arithmetic exact (initial + successful writes); per-reader version sequences non-decreasing |
| E2E (smoke) | server running on the target URL; no other test running | `node scripts/api-smoke.mjs <url>` → **42/42 green**; suite self-resets at the end |
| Manual | checklists written and reviewed | security spot-check list executed with findings logged (defects → `DEFECT_LOG.md`); usability checklist completed by ≥1 real user outside the team |

### 3.2 Phase gates

| Gate | Criteria to pass |
|---|---|
| Phase 1 → 2 (parity) | smoke 42/42 against Spring Boot; plan + case register reviewed against the contract |
| Phase 2 → 3 (testing complete) | ≥35 cases documented **and executed**; unit/integration/concurrency/load levels green; JaCoCo report attached; security + usability documents done; defect log current (every entry has a status); `TEST_SUMMARY.md` written with planned-vs-executed and pass/fail |
| Phase 3 (post-deploy) | smoke 42/42 against the live cloud URL; results appended to the summary |

---

## 4. Tools

| Tool | Source / version | Purpose | Notes |
|---|---|---|---|
| JUnit 5 (Jupiter) | via `spring-boot-starter-webmvc-test` / starter-test in `backend/pom.xml` | all automated tests | run: `cd backend && ./mvnw test` (`mvnw.cmd` on Windows) |
| MockMvc | `spring-boot-starter-webmvc-test` | HTTP-level integration tests without binding a port | full stack up to the servlet layer; exact status/body/header assertions |
| JaCoCo | `jacoco-maven-plugin` (Maven) | statement & branch coverage report | report at `backend/target/site/jacoco/`; attached to `TEST_SUMMARY.md` |
| Maven Wrapper | `backend/mvnw`, `mvnw.cmd` | reproducible builds, no global Maven | requires JDK 21 (Temurin, per D6) |
| `scripts/api-smoke.mjs` | project, dependency-free Node | 42-check E2E contract regression | `node scripts/api-smoke.mjs [baseUrl]`; mutates then **resets** (self-cleaning) |
| Custom load harness | project, Node 20 (planned as `scripts/load-harness.mjs`) | N concurrent readers + writers → latency percentiles, lost-update & monotonicity checks | same dependency-free style as the smoke suite; results pasted into `TEST_SUMMARY.md` |
| Portable Node 20.20.2 | `.tools/node20` (gitignored) | runs all scripts on the documented Node floor | DEF-009 guard rail — never rebuild native modules under a newer Node |
| Spring Boot Actuator | `spring-boot-starter-actuator` | `/actuator/health` readiness for dev/e2e scripts | used by `scripts/dev.mjs` and the harness to wait for boot |
| Browser devtools + `curl` | manual | security spot checks, header verification, exploratory probes | findings → defect log or security document |

---

## 5. Environments

| Environment | OS / toolchain | Base URL | Data store | Purpose |
|---|---|---|---|---|
| Local dev | Windows (project machine), Temurin JDK 21 via Maven Wrapper, portable Node 20.20.2 in `.tools/node20` | Vite dev `http://localhost:5173` (proxied) · backend `http://localhost:8080` | `JsonFileStore` (temp-dir JSON, per DEF-005 lesson) | development, all automated levels, load test |
| Test (in-JVM) | same machine, MockMvc | none (no socket) | in-memory `StateService` seeded from `classpath:/seed.json` | unit + integration + concurrency suites |
| CI (Phase 3) | GitHub Actions, headless Linux runner | localhost in the job | same as local | typecheck → `mvnw test` + JaCoCo → build → docker (per `agent.md` §4 Phase 3) |
| Cloud (Phase 3) | Render free web service (D5) | `https://<deployed-url>` | `PostgresStore` (D2) | post-deployment smoke only; cold-start delays tolerated with a health wait |

Environment notes: every process reseeds on boot (§6), so test runs are
repeatable regardless of prior state; only one mutating suite may run against a
server at a time (the smoke suite and MockMvc never share a server — MockMvc
runs in-JVM).

---

## 6. Test data management

| Rule | Detail |
|---|---|
| Boot reseed | every backend start reloads `classpath:/seed.json`, applies the DEF-011 whole-day shift, and bumps the persisted `version` — a known-good baseline before any run |
| `POST /api/reset` | full reseed at `version + 1`; the canonical way to return to baseline mid-session (seed counts 12/41/12/14/9, `payments: []`) |
| Smoke self-cleaning | `api-smoke.mjs` resets the seed at the end of every run, so consecutive runs are independent (contract guarantee) |
| Suite isolation | each MockMvc test class starts and ends with `POST /api/reset`; the state singleton is shared in-JVM, so order independence is enforced by resetting, not by hoping |
| Test-row conventions | rows created by tests use identifiable payloads (`Smoke test …`, emails `smoke@campus.edu`) and are cleared by reset; user-create tests use unique emails per run |
| Clock-dependent data | reminder-window and payment-expiry cases use **unit tests with an injected/fabricated clock** (±1 s margins); no test asserts an absolute timestamp over HTTP except seed relativity (DEF-011), which asserts *relative* order (`scheduledFor` > now) |
| Outbox file | the reminder outbox is JSONL appended per run; tests read it from a temp location and truncate between runs so line counts are per-test |
| Payments seed | `payments: []` at seed; every purchase case asserts both the row and the `version` delta, so leaked rows are detected by later baseline assertions |

---

## 7. Defect management

**Log:** `docs/testing/DEFECT_LOG.md` — every defect gets a summary-table row
**and** a detail entry (repro steps, root cause, fix, lesson). The log runs
**live during coding** (`agent.md` §3: honest defect reporting earns marks), not
as a post-hoc write-up.

**Definitions (as used in the log):**

| Severity | Meaning | Priority | Meaning | Status values |
|---|---|---|---|---|
| Critical | app unusable / data wrong | P1 | fix now | Open · In progress · Fixed · Verified · Closed (won't fix) |
| Major | feature broken, no workaround | P2 | fix this phase | |
| Minor | feature impaired, workaround exists | P3 | fix when convenient | |
| Cosmetic | look & feel only | | | |

**Workflow:**

1. Found during any level (or by probing, as DEF-010 was) → add summary row +
   detail entry immediately, with exact repro steps and the observed vs expected
   response.
2. Assign severity/priority; set status `Open`.
3. Fix (code) → status `Fixed`; add or extend a case in `TEST_CASES.md` that
   would have caught it.
4. Re-run the affected level **plus** the smoke suite → status `Verified` when
   green, evidence noted in the entry (as DEF-009/DEF-010 do).
5. Contract mismatches found while porting are defects too (DEF-010 pattern):
   fix the code, then correct `API_CONTRACT.md` to state the contract.

Reference entries: DEF-010 (validation moved from schema to application — the
probe sets in TC-066/TC-067 are its regression net), DEF-011 (boot-relative
seed dates — TC-002), DEF-012 (reminder starvation under frozen dates — the
reminder window cases).

---

## 8. Risks & mitigation

| ID | Risk | Impact | Mitigation |
|---|---|---|---|
| R-01 | Contract rows for premium/reminders land while cases are being written | cases reference a moving target | the endpoint spec supplied for this plan is treated as authoritative; traceability updated when `API_CONTRACT.md` gains the rows; smoke suite extended separately |
| R-02 | Shared mutable `StateService` singleton across MockMvc tests | order-dependent failures, false greens | reset before/after each test class (§6); unique payloads; no reliance on prior test state |
| R-03 | Clock-dependent flakiness (reminder window, payment expiry, seed shift) | intermittent failures erode trust | unit tests with injected clock and ±1 s margins; only *relative* time asserted over HTTP; reminder cases re-run right after boot |
| R-04 | Absolute latency targets are machine-dependent | misleading pass/fail on load | report percentiles as measurements; thresholds are *local targets* (p95 ≤ 500 ms); cloud re-baselined separately |
| R-05 | Concurrency tests are inherently timing-sensitive | flaky suite | latch-synchronised starts; assert invariants (version arithmetic, monotonicity, no lost updates), never timing; 10-run repetition in the exit criteria |
| R-06 | `proposedSlots` accepts non-ISO strings (contract defines count only, no format check) | malformed `scheduledFor` reaching the reminder job | documented as current behaviour in TC-016; reminder job must parse defensively (skip + log unparseable instants); any stricter validation is a contract change, raised as such |
| R-07 | Native/Node ABI trap (DEF-009) | toolchain breaks mid-testing | retired: the retired Node backend's `better-sqlite3` dependency was removed from `package.json` when the Java backend became the only one, so a fresh `npm install` builds no native module; scripts still run under `.tools/node20` for reproducibility |
| R-08 | Defect log drifts behind the coding | loses assignment marks | live-log rule (§7); log reviewed at every gate |
| R-09 | Render cold starts / idle sleep skew post-deploy smoke | false failure at Phase 3 | health-wait + one retry in the smoke invocation; evidence recorded with the URL |
| R-10 | Coverage percentage gamed by trivial tests | hollow evidence | branch coverage target + contract traceability (every contract area mapped to ≥1 case, §10), not the percentage alone |

---

## 9. Schedule & milestones

| Milestone | Deliverable | Depends on | Gate / evidence |
|---|---|---|---|
| M1 — Plan & cases (Phase 2 open) | `TEST_PLAN.md`, `TEST_CASES.md` (72 cases, EP/BVA/decision tables) | contract pinned | review against `API_CONTRACT.md` |
| M2 — Unit suites | JUnit 5 tests for engine, coercions, seed clock, gateway, expiry, reminder logic | services implemented | `mvnw test` green |
| M3 — Integration suite | MockMvc: ported smoke checks + DEF-010 probes + premium + reminders + headers | endpoints implemented | suite green; contract row coverage (§10) |
| M4 — Concurrency suite | lock & cache invariant tests | `StateService`/`MatchService` stable | 10 green runs |
| M5 — Load run | harness report (p50/p95/p99, integrity) | M3 green locally | report pasted into summary |
| M6 — Coverage & non-functional | JaCoCo report; security spot-check; usability checklist with real users | M2–M5 | thresholds met (§3.1) |
| M7 — Regression & summary | smoke re-run; `TEST_SUMMARY.md` (planned vs executed, pass/fail, coverage, defects, open issues, lessons) | M2–M6 | Phase 2 → 3 gate |
| M8 — Post-deploy smoke (Phase 3) | `api-smoke.mjs https://<url>` evidence | deployment | parity gate 3 |

---

## 10. Traceability — contract area → test level → cases

(`Contract` = `docs/testing/API_CONTRACT.md`; `smoke §n` = the grouped checks in
that document's "What the smoke suite covers"; case IDs are in `TEST_CASES.md`.)

| Contract area | Primary level(s) | Case IDs | Smoke / defect reference |
|---|---|---|---|
| Snapshot semantics, ordering, headers, version stamp | integration, load | TC-001, TC-070, TC-071 | smoke §1 |
| Seed invariants, boot reseed, date relativity | E2E, integration | TC-001, TC-002 | smoke §1 · DEF-011 |
| `POST /api/reset` | integration, E2E | TC-003, TC-043 | smoke §8 |
| Users — create & handle derivation | integration | TC-066 (probe set) | smoke §6 |
| Users — PATCH (`format`, skills, quirks) | integration | TC-018…TC-024, TC-062, TC-066 | smoke §5 · DEF-010 |
| Sessions — create & `proposedSlots` | integration | TC-012…TC-017 | smoke §2, §7 |
| Sessions — state machine & error paths | integration | TC-025…TC-032 | smoke §2 · decision table DT |
| Reviews — rating EP/BVA, rounding, duplicate | integration | TC-004…TC-011 | smoke §3, §7 · DEF-010 |
| Messaging & idempotent mark-read | integration | TC-061, TC-067 | smoke §4 |
| Matching engine (weights, rounding, ordering, cache) | unit, integration | TC-044…TC-051 | new endpoint (no smoke row yet) |
| Premium — purchase, gateway, 402 no-bump | unit, integration | TC-033…TC-039 | new endpoint · D4 |
| Premium — stats, `active` expiry, payments ordering | unit, integration | TC-040…TC-043 | new endpoint · BVA |
| Reminders — window, dedupe, outbox, stats, read-only | unit, integration | TC-052…TC-058 | new endpoint · DEF-012 |
| Version bump rules (success/failure/no-op) | integration, concurrency, load | TC-059…TC-062, TC-071 | §Snapshot semantics |
| Locking under concurrency | concurrency | TC-063…TC-065 | StateService RW lock |
| Error paths & exact messages (DEF-010) | integration | TC-066, TC-067 | DEF-010 probe table |
| Routing matrix (405/404), malformed JSON | integration | TC-068, TC-069 | contract §Endpoints |
| Performance (latency percentiles) | load | TC-071 | Phase 2 load deliverable |
| Security spot checks, usability | manual | separate documents | Phase 2 non-functional deliverables |
