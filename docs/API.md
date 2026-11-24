# HTTP API

Twofold's React client talks to a JSON API served by the **Spring Boot backend**
(`backend/`, Java 21). In development Vite proxies `/api` and `/actuator` to
`http://localhost:8080`; the packaged build is **intended** to serve the compiled React
bundle from `static/`, so production is one process and one port. That packaging step is
not built yet — the cloud design and its remaining gaps are in [`CLOUD.md`](CLOUD.md).

Implementation: `backend/src/main/java/com/twofold/backend/http/ApiController.java`
(one dispatcher, method + path). Persistence is `store/JdbcStorage` over a local SQLite
database (`backend/twofold.db`) behind the `Storage` interface. On the client,
`src/data/api.ts` is the only data seam: reads are synchronous over the cached snapshot,
writes are HTTP calls that return the updated snapshot.

## Authentication

Writes require a **server-side session** (Task 2). Login issues an opaque 256-bit token
stored in the `auth_sessions` table and delivered as a cookie:

```
Set-Cookie: twofold_session=<token>; Path=/; Max-Age=604800; HttpOnly; SameSite=Lax
```

- `HttpOnly` — JavaScript cannot read the token (an XSS cannot steal it).
- `SameSite=Lax` — a cross-site POST cannot ride the cookie, so no CSRF token is needed
  for this same-origin app.
- **Bearer tokens are not accepted.** The cookie is the only transport; that is why the
  frontend calls `fetch(..., { credentials: 'same-origin' })` everywhere.
- Sessions last 7 days and **slide**: once less than half the lifetime remains, the next
  request extends the expiry without rotating the token. Logging out deletes the row, so
  replaying the old cookie fails.
- Passwords are BCrypt hashes (`accounts.password_hash`); plaintext is never stored,
  returned or logged. `GET /api/auth/me` never includes the hash.

**No bootstrap accounts (Task 3).** The 12 seeded students are profiles and content only:
they have **no `accounts` row**, so login against them always fails with `401` and there is
no shared password anywhere in the codebase. The only accounts that exist are the ones
created through `/api/auth/register`. `POST /api/reset` clears `accounts` and
`auth_sessions` along with the data and **does not recreate** them.

### `POST /api/auth/register`

```json
{ "name": "Priya Nambiar", "email": "priya@campus.edu", "password": "at-least-8",
  "branch": "Computer Science", "year": "2nd year", "bio": "optional" }
```

`201` with `{ "user": User, "database": Database }` and the session cookie. `400` for a
missing field, a password shorter than 8 characters, a duplicate email or a taken handle.
The profile defaults match `POST /api/users`.

### `POST /api/auth/login`

```json
{ "email": "priya@campus.edu", "password": "the-password-you-chose" }
```

`email`, `handle` or `identifier` is accepted (case-insensitive). `200` with
`{ "user": User, "database": Database }` and the session cookie; `401`
`{"error":"invalid email or password"}` for an unknown account *and* a wrong password
(the same message, so login does not confirm which emails exist).

### `POST /api/auth/logout`

`200` `{"ok":true}` and an expired cookie. The session row is deleted — the token is dead.

### `GET /api/auth/me`

`200` `{ "user": User }` when the cookie is valid, `401` `{"error":"not signed in"}`
otherwise. The frontend boots from this probe rather than trusting local state.

## Snapshot

### `GET /api/state`

Returns the complete client state:

```ts
interface Database {
  version: number      // monotonic, +1 per successful write
  users: User[]        // teaches/wants are ExchangeSkill[] hydrated in the document
  skills: Skill[]      // 41 across 8 categories
  sessions: Session[]
  reviews: Review[]
  messages: Message[]  // readBy[] drives unread badges
  payments: Payment[]  // Twofold Pro ledger (D4); empty until a sandbox capture
}
```

Dates are ISO 8601 strings; the snapshot uses camelCase domain objects. A polling client
skips a re-render when `version` is unchanged. `NoStoreFilter` adds
`Cache-Control: no-store` to every `/api/*` response.

**Everything but the landing needs a session.** `GET /api/stats` is the only public read:
aggregate counts plus three sanitized featured cards (no email, no credential fields), so
the marketing page renders for a signed-out visitor. `GET /api/state`, `/api/matches`,
`/api/premium` and `/api/reminders` all answer `401 authentication required` without a
cookie — the snapshot exposes every user row, including emails, which is exactly what the
landing must not hand out.

`POST /api/reset` is the one unauthenticated write, and it is **fail-closed**: gated by
`twofold.reset.enabled`, `false` in `application.properties`. When disabled it answers
`403 {"error":"reset is disabled on this deployment"}` before touching anything. The dev
loop opts in through `scripts/dev.mjs` (`TWOFOLD_RESET_ENABLED=true`); the test suite sets
the property itself.

## Endpoints

All writes send JSON and return the **updated snapshot** (`201` for creates, `200` for
other writes). Errors are `{ "error": "message" }`.

| Method and path | Body | Effect |
|---|---|---|
| `GET /api/stats` | — | **Public.** Aggregate counts, three sanitized featured profiles (name/handle/branch/year/hue/skills/rating — never email), one review quote, hero match score. The landing page's data source |
| `GET /api/state` | — | Full snapshot (requires a session) |
| `GET /api/matches?userId=…[&mode=all]` | — | Ranked matches from the Java engine (D3). Default keeps only people who can teach you something; `mode=all` scores everyone (Discover) |
| `GET /api/premium?userId=…` | — | `{ userId, active, plan, since, until, payments }`, derived from the ledger |
| `GET /api/reminders` | — | Reminder job status + outbox path and last run (observability for the `@Scheduled` job) |
| `POST /api/auth/register` | `{ name, email, password, branch?, year?, bio? }` | Create a credentialed account and sign in |
| `POST /api/auth/login` | `{ email\|handle\|identifier, password }` | Sign in |
| `POST /api/auth/logout` | `{}` | End the session server-side |
| `GET /api/auth/me` | — | The signed-in user, or 401 |
| `POST /api/users` | `{ name, email, branch?, year?, bio? }` | Create a profile **without credentials** (kept for tests/back-compat; the UI registers instead). Requires a session |
| `PATCH /api/users/:id` | any of `name`, `branch`, `year`, `bio`, `format`, `availability`, `teaches`, `wants` | Update your own profile (`403` for anyone else); supplied skill lists replace the old ones |
| `POST /api/sessions` | `{ teacherId, learnerId, skillId, topic, proposedSlots, note? }` | Request a session as the learner (`learnerId` must be you); 1–3 slots, teacher ≠ learner |
| `POST /api/sessions/:id/accept` | `{ slot }` | Confirm at one offered slot (participants only) |
| `POST /api/sessions/:id/decline` | `{}` | Decline (participants only) |
| `POST /api/sessions/:id/cancel` | `{}` | Cancel (participants only) |
| `POST /api/sessions/:id/complete` | `{}` | Mark complete (participants only) |
| `POST /api/reviews` | `{ sessionId, fromUserId, toUserId, rating, text, role }` | One review per author per session; `fromUserId` must be you |
| `POST /api/messages` | `{ fromUserId, toUserId, body }` | Send a non-empty message as yourself |
| `POST /api/messages/read` | `{ userId, partnerId }` | Mark the partner's messages read — for your own inbox only |
| `POST /api/premium` | `{ userId, plan, cardToken }` | Sandbox purchase for yourself. `201` + ledger row on capture; **`402`** `{ error }` on decline, with **no** state change and **no** version bump |
| `POST /api/reset` | `{}` | Development-only: wipe → reseed → `version + 1`, clearing credentials and sessions. `403` unless `twofold.reset.enabled=true` |

### Actor enforcement

Every mutating endpoint identifies the actor **from the session cookie**, never from a
client-supplied `userId` / `fromUserId`:

| Situation | Response |
|---|---|
| No/invalid/expired cookie on a write | `401` `{"error":"authentication required"}` |
| Cookie valid, body names a different user | `403` `{"error":"… as yourself"}` (or `you can only edit your own profile`, `you are not a participant in this session`, …) |
| Cookie valid, body matches the actor | normal `200`/`201` |

`401`/`403` happen **before** any mutation, so the version never bumps.

### Status codes

| Code | When |
|---|---|
| `200` / `201` | Successful write (snapshot returned) |
| `400` | Validation failure — unknown user/skill, bad rating, empty message, bad slot format, unknown plan, slot already taken, duplicate review, short password |
| `401` | No valid session (`not signed in` on `/auth/me`), or bad credentials on login |
| `402` | Sandbox payment declined (only `/api/premium`) |
| `403` | A valid session trying to act as another user, or `POST /api/reset` while the reset flag is off |
| `404` | Unknown route |
| `405` | Known route, unsupported method |
| `500` | Unexpected server error — logged server-side with an API prefix |

`POST /api/users` intentionally has no ownership check: it creates a *new* profile without
credentials rather than acting as an existing one, and it is not reachable from the UI
(registration is). Only accounts created by `/api/auth/register` can sign in.

## Persistence and boot

`JdbcStorage` writes the whole snapshot in **one SQLite transaction** (`backend/twofold.db`,
WAL journalling). The database is **outside `target/`** so a rebuild cannot wipe it; the
path is logged at boot:

```
twofold db: D:\…\backend\twofold.db
twofold state ready: v42 — 12 users, 41 skills, 12 sessions
```

- **First boot** creates the schema (`db/migration/V1__domain.sql`, `V2__auth.sql`,
  versioned with `PRAGMA user_version`) and seeds from `seed.json` at version 1.
- **The retired JSON store is imported once.** If `target/twofold-data/state.json` exists
  and the database is empty, its rows are imported and the file is renamed
  `state.json.migrated`, so it can never overwrite newer data.
- **Restarts load what was written** — they no longer reseed — and the snapshot version is
  preserved, so durability is visible in the demo. `POST /api/reset` is the explicit
  "wipe → reseed → version + 1" path.
- Seed timestamps are still shifted to "today" by `SeedClock` whenever the seed is written.

Override the paths:

```bash
java -jar backend/target/twofold-backend-0.0.1-SNAPSHOT.jar \
  --twofold.db=target/load-data/twofold.db \
  --twofold.store=target/load-data/state.json \
  --twofold.reminder.outbox=target/load-data/outbox.jsonl
```

(`twofold.store` is the *legacy* JSON path, used only for the one-way import.)

See [DATA_MODEL.md](DATA_MODEL.md) for the schema, invariants and seed contents.

## Client data API

Components use `src/data/api.ts`, never `fetch` directly. Synchronous reads select from
the cached snapshot (`listUsers`, `getUser`, `listSessionsForUser`, `listThreads`…). Async
writes call the endpoints above (`updateUser`, `requestSession`, `acceptSession`,
`addReview`, `sendMessage`…) plus `login`, `register`, `logout`, `restoreSession`,
`fetchMatches`, `fetchPremiumStatus`, `purchasePremium` and the public `fetchPublicStats`
(used by the landing and auth pages). There is no client-side reset call.
`src/data/store.ts` owns the snapshot cache, polling and the signed-in id (never the
token); `src/context/AppContext.tsx` exposes it through React context, and
`src/data/useMatches.ts` caches server matches per snapshot version.
