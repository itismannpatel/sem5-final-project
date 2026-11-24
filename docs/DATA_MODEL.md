# Data model

Twofold stores its data in a **local SQLite database** (`backend/twofold.db`), read into an
immutable in-memory snapshot (`Database`) that is also what `GET /api/state` returns — the
browser renders straight from it, so the API shape and the read model are deliberately one
thing. Writes go the other way: a mutation produces a new snapshot, which `JdbcStorage`
persists in a single transaction.

| Concern | Where |
|---|---|
| Domain records (the snapshot's shape) | `backend/src/main/java/com/twofold/backend/domain/*.java` |
| Schema (versioned) | `backend/src/main/resources/db/migration/V1__domain.sql`, `V2__auth.sql` |
| Connection + migrations | `store/Db.java` (`PRAGMA user_version`, WAL) |
| Load / transactional save / legacy import | `store/JdbcStorage.java` |
| Single-writer lock + version bump | `state/StateService.java` |
| Credentials + session rows | `auth/JdbcAuthRepository.java`, `auth/AuthService.java` |
| Seed data | `backend/src/main/resources/seed.json` + `state/SeedClock.java` |
| Client types | `src/data/types.ts` (mirrors the snapshot) |
| Server contract | `docs/API.md`, executed by `scripts/api-smoke.mjs` |

## Snapshot shape

```jsonc
{
  "version": 42,             // monotonic; +1 per successful write
  "users":   [ … ],          // 12 seeded
  "skills":  [ … ],          // 41 across 8 categories
  "sessions":[ … ],          // 12 seeded, every status represented
  "reviews": [ … ],          // 14 seeded
  "messages":[ … ],          // 9 seeded
  "payments":[ … ]           // Twofold Pro ledger; [] until a sandbox capture
}
```

All timestamps are ISO 8601 strings (`java.time.Instant` serialised by `JacksonConfig`).
`version` is the client's polling key: an unchanged version means "skip the re-render".

### Records

| Record | Fields (JSON order) | Notes |
|---|---|---|
| `User` | `id`, `name`, `handle`, `email`, `branch`, `year`, `bio`, `teaches`, `wants`, `availability`, `format`, `joinedAt`, `hue`, `verified` | `teaches`/`wants` are `ExchangeSkill[]`, hydrated from `user_skills` in chosen order |
| `ExchangeSkill` | `skillId`, `level` | `level` ∈ Beginner / Intermediate / Advanced; `skillId` must exist |
| `Skill` | `id`, `name`, `category` | Static catalogue; the 41 skills are not user-editable (glyph metadata lives in `src/data/catalog.ts`) |
| `Session` | `id`, `teacherId`, `learnerId`, `skillId`, `topic`, `status`, `proposedSlots[]`, `scheduledFor`, `createdAt`, `completedAt`, `note` | Directional: one teacher → one learner; `scheduledFor` set on accept, `completedAt` on complete |
| `Review` | `id`, `sessionId`, `fromUserId`, `toUserId`, `rating`, `text`, `createdAt`, `role` | `rating` 1–5; `role` describes what the **recipient** did (`taught`/`learned`) |
| `Message` | `id`, `fromUserId`, `toUserId`, `body`, `createdAt`, `readBy[]` | Threads are implicit: an unordered pair of user IDs |
| `Payment` | `id`, `userId`, `plan`, `amountMinor`, `currency`, `cardToken`, `status`, `createdAt` | `amountMinor` in paise; `cardToken` stored **masked** (`****4242`) |

## Schema

Nine domain tables plus two auth tables. Ids stay the contract's strings; list order that
matters is a `position` column; the snapshot version lives in `meta`.

| Table | Columns | Notes |
|---|---|---|
| `meta` | `key`, `value` | only `version` today; its **absence** means "no snapshot yet" (fresh database) |
| `users` | `id`, `name`, `handle`, `email` (NOCASE unique), `branch`, `year`, `bio`, `availability` (JSON array), `format` (CHECK), `joined_at`, `hue`, `verified` | `handle` unique |
| `skills` | `id`, `name`, `category` | referenced by skills and sessions |
| `user_skills` | `user_id`, `kind` (`teaches`/`wants`), `skill_id`, `level`, `position` | PK `(user_id, kind, skill_id)`; FKs to users/skills; order preserved |
| `sessions` | `id`, `teacher_id`, `learner_id`, `skill_id`, `topic`, `status` (CHECK), `scheduled_for`, `created_at`, `completed_at`, `note` | nullable columns model the JSON's omitted keys |
| `session_slots` | `session_id`, `position`, `slot` | FKs to sessions, cascade |
| `reviews` | `id`, `session_id`, `from_user_id`, `to_user_id`, `rating` (CHECK 1–5), `text`, `created_at`, `role` | `UNIQUE(session_id, from_user_id)` backs the duplicate-review no-op |
| `messages` | `id`, `from_user_id`, `to_user_id`, `body`, `created_at` | |
| `message_reads` | `message_id`, `position`, `user_id` | read receipts in order |
| `payments` | `id`, `user_id`, `plan`, `amount_minor`, `currency`, `card_token`, `status`, `created_at` | ledger only; declines never insert |
| `accounts` | `user_id` (PK), `password_hash`, `created_at` | **BCrypt only** — plaintext is never stored or logged. Seeded students have **no row here** (Task 3), so they cannot log in |
| `auth_sessions` | `token` (PK), `user_id`, `created_at`, `expires_at` | opaque 256-bit token, 7-day sliding expiry |

Schema changes are forward-only migrations in `db/migration/V#__name.sql`, applied at boot
when `PRAGMA user_version` is behind and committed with a new version stamp. Flyway was the
alternative; `user_version` avoids a dependency and a second config surface for two scripts.
The tradeoff — no checksums or undo — is acceptable for a local single-writer instance and
is noted in `agent.md` §7.

**Auth tables deliberately have no foreign key to `users`.** The store rewrites every
domain table on each write (see below); a cascading FK would delete live login sessions on
every message. Users are never deleted by the API, and credentials are keyed by the same id.

## Invariants

Held after every write, asserted across the suite:

1. **`version` increases by exactly one** per successful write. A failed write (400/401/402/403)
   changes nothing and does not bump the version.
2. **Referential integrity** in the database: every `teacher_id`, `learner_id`, `skill_id`,
   `from_user_id`, `to_user_id` and `review.session_id` points at an existing row (FKs are
   enforced with `PRAGMA foreign_keys=ON`). The API validates first and answers `400`, never
   a raw constraint error.
3. **Teacher ≠ learner**, and a session names an existing skill.
4. **One review per (session, author)** — a second review is a `201` no-op without an insert.
5. **Only captured payments are stored**; a decline throws before any mutation.
6. **Messages are non-empty** (whitespace-only is rejected).
7. **Derived values are never stored**: match scores, rating averages, unread counts and
   pending-request counts are computed on read.
8. **Session status is not a guarded state machine** — a terminal state can be overwritten
   (`declined → confirmed`). Retained Node-parity behaviour, documented as `TC-029`.
9. **The actor is the session, not the payload**: a write whose `userId`/`fromUserId` is not
   the signed-in student is rejected `403` before touching state.

## Lifecycle

```
proposed ──accept──▶ confirmed ──complete──▶ completed
   │                    │
   ├──decline──▶ declined
   └──cancel ──▶ cancelled
```

Ratings and reviews attach after `completed`. Editing a profile's skills re-ranks every
match immediately because scores are derived, never cached.

Login lifetime: `auth_sessions.expires_at` is 7 days from issue and slides while active;
logout deletes the row; expired tokens are pruned on the next login.

## Persistent writes

`JdbcStorage.save()` deletes and re-inserts every table inside **one transaction**, then
writes `meta.version`, so a reader can never observe half a write and a crash leaves the
previous snapshot intact. `StateService` still owns the single-writer `ReentrantReadWriteLock`
and only persists when a mutation returns a *new* document (that is how no-op paths stay
bump-free).

The cost is proportional to snapshot size, which is fine for a campus demo (hundreds of
rows, ~10 ms per write measured in the load harness) but is the honest scaling limit: a
bigger deployment moves to row-level writes or a real server database. The `Storage`
interface is the seam for that.

Files: `backend/twofold.db` plus SQLite's `-wal`/`-shm` sidecars. All three are git-ignored;
no binary data is committed. `npm run db:reset` deletes them so the next boot creates and
seeds a fresh database.

## Seed, reset and migration

`seed.json` is the source of truth for demo data; `SeedClock` rewrites relative timestamps
whenever the seed is written, so "in 3 days" is always true. Current seed: **12 users, 41
skills, 12 sessions (all five statuses), 14 reviews, 9 messages, 0 payments**, anchored at
`seededAt 2026-11-21T12:00:00.000Z` (a seed-file field used only for the clock shift — it is
not part of the snapshot).

- **Fresh database** → schema created, seed written at `version 1`. The 12 seeded students
  are profiles only: no `accounts` row is written, so there is no bootstrap password.
- **Restart** → the database is loaded as-is (no reseed, version preserved). This is the
  durability proof: register or send a message, restart, and it is still there.
- **`POST /api/reset`** (development only, `twofold.reset.enabled=false` by default) →
  wipe → reseed → `version + 1`. Credentials and sessions are cleared with the rows and are
  **not** re-created; nothing about the seed grants a login.
- **Legacy import** → on an empty database, an existing `target/twofold-data/state.json` is
  imported once and renamed `.migrated`. The JSON file is no longer written to.

## Why SQLite

A real database (queryable, durable, transactional, constraints) with no server to install
keeps `npm run dev` one command and the demo offline. It is also the natural stepping stone
to the cloud plan in `docs/cloud/DEPLOYMENT_PLAN.md`: `Storage` is the seam, so a managed
Postgres implementation can replace `JdbcStorage` without touching the API or the UI.
