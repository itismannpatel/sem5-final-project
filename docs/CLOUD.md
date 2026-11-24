# Cloud deployment — decided design

> **STATUS: DECIDED 2026-11-25 · NOT YET BUILT · NOT YET DEPLOYED.**
> The team chose **Option C**: one Render Free Docker service + Neon Free Postgres +
> object storage for backups + GitHub Actions. Render and Neon accounts already exist.
> This document is the repo-facing cloud architecture; `docs/cloud/` holds the internal
> working drafts and is not committed. Provider numbers were verified on 2026-11-22 and
> re-checked on 2026-11-25 — **re-verify at signup**, free tiers change.

## 1. The decision

| Question | Answer |
|---|---|
| Hosting | **Render Free web service**, Docker, `singapore` region |
| Database | **Neon Free Postgres** (managed; replaces the local SQLite file in the cloud) |
| Object storage | **Open:** Railway Bucket (10 GB free, S3-compatible) preferred; Supabase Storage an alternative — both need a new free account |
| CI/CD | **GitHub Actions** on the public repo (free for public repositories) |
| Frontend | Built into the same jar and served by Spring Boot — one origin, one URL |
| Cost | ₹0 on free tiers, no credit card; first paid step is Render Starter at $7/month |

Chosen over Railway + volume because the brief explicitly asks for a **managed
database** and **object storage**, and over the ephemeral-SQLite option because judge
accounts must survive restarts. Neon never pauses for inactivity (compute scales to
zero and wakes in milliseconds), unlike Supabase's one-week pause.

## 2. Architecture

```
Browser
  │ HTTPS
  ▼
Render Free web service (Docker, singapore, 0.1 CPU / 512 MB)
  ├── /            → React bundle copied into the jar's static/
  ├── /api/*       → ApiController (session-gated reads)
  ├── /actuator/health
  └── SPA fallback → index.html for client-side routes
        │  JDBC (TLS), small pool
        ▼
Neon Free Postgres  (users, skills, sessions, reviews, messages, payments, accounts)

GitHub Actions ── pg_dump (nightly) ──▶ S3-compatible bucket (backups)
             └── build + tests + smoke + docker build + Render deploy hook
```

Single service on purpose: the app is same-origin by design (`NoStoreFilter`, relative
`/api`, `SameSite=Lax` cookie). Splitting a static site from the API would add CORS and
`SameSite=None` for no benefit.

## 3. Services and limits (free tier)

| Service | Role | Limits that matter |
|---|---|---|
| Render Free web service | runs the jar, terminates TLS | 0.1 CPU / 512 MB, 750 instance-hours/month per workspace, 5 GB bandwidth, 500 build minutes; **spins down after 15 min idle, wakes in ~1 min**; ephemeral disk |
| Neon Free Postgres | durable application database | 1 GB storage/project, 100 CU-hours/month, 5 GB egress; compute scales to zero after 5 min, wakes in ms; **never paused for inactivity**; no card |
| Object storage (TBD) | nightly database backups | Railway Bucket: 10 GB-month, S3-compatible, no card; Supabase Storage: 1 GB (card policy unverified) |
| GitHub Actions | CI/CD | free standard runners on public repos; no quota draw-down |
| `/actuator/health` | readiness + monitoring | already implemented; Render health check points here |

## 4. What must change in this repository

Nothing here is built yet. Ordered by risk:

| # | Change | Current state | Why it is needed |
|---|---|---|---|
| 1 | **`PostgresStore implements Storage`** + schema + migrations + tests | only `JdbcStorage` (SQLite) exists | Neon cannot be used otherwise; this is the largest and riskiest item |
| 2 | **Store selection** | `StorageConfig` always builds SQLite; note `twofold.store` / `TWOFOLD_STORE` already means the *retired JSON import path*, so the new switch needs a different name (e.g. `twofold.db.kind=sqlite\|postgres` + `DATABASE_URL`) | pick Postgres in the cloud, keep SQLite local/tests |
| 3 | **Dockerfile + `.dockerignore`** | neither exists | required by the brief; multi-stage node:20 → JDK 21 → JRE 21, non-root, `-XX:MaxRAMPercentage=75`, healthcheck |
| 4 | **Frontend in the jar** | `backend/src/main/resources/` has no `static/`; `pom.xml` has no copy step; Vite writes the root `dist/` | without it the deployed jar serves no UI (`SpaController` only forwards `/api`) |
| 5 | **SPA deep-link fallback** | `BrowserRouter`; only `/api` forwards to `index.html` | a refresh on `/discover` currently 404s in a packaged build |
| 6 | **`PORT` + forwarded headers** | no `server.port`; app always binds 8080 | Render injects `PORT`; forwarded headers make `request.isSecure()` true behind TLS |
| 7 | **`Secure` session cookie** | `ApiController` sets `HttpOnly; SameSite=Lax; Path=/` only | cookie should be `Secure` when the request arrived over HTTPS |
| 8 | **CI workflow** | none | build, typecheck, 89 tests, smoke, docker build, deploy hook |
| 9 | **Smoke without reset** | suite calls `/api/reset`, correctly `403` in production | add a no-reset mode for post-deploy checks |
| 10 | **Hardening** | no security headers, no body cap | pre-deploy gate in `docs/testing/SECURITY_SPOT_CHECK.md` §4 |
| 11 | **Backup job** | none | nightly `pg_dump` → bucket, 7-day retention |

## 5. Configuration and secrets

Set in Render's environment (never in the repo):

| Variable | Value |
|---|---|
| `PORT` | injected by Render |
| `DATABASE_URL` | Neon pooled connection string (TLS required) |
| `TWOFOLD_DB_KIND` (name TBD) | `postgres` |
| `TWOFOLD_RESET_ENABLED` | **unset** — reset stays fail-closed (`403`) |

GitHub secrets: `RENDER_DEPLOY_HOOK`, `NEON_DATABASE_URL` (backup job), bucket
credentials. No password, key or connection string is committed; the seed contains
profiles only and **no account rows**, so there is no shared password to leak.

## 6. Data, backup and recovery

- Schema mirrors the snapshot: one table per collection plus `meta(version)`; `load()`
  assembles the same immutable `Database`, `save()` writes in one transaction.
- Seeding runs only when the database is empty; timestamps shift to "today" at boot, so
  a dormant demo still shows upcoming sessions.
- Accounts are created only through `/api/auth/register`; the cloud database starts with
  the 12 seeded profiles and zero logins.
- **Backup:** a scheduled workflow runs `pg_dump` nightly and uploads to the bucket with
  a dated key; keep 7 days. Restore is `psql "$DATABASE_URL" < dump.sql` followed by one
  health check and a smoke run.
- Neon also keeps its own restore history on the free plan — confirm the current window
  in the console at signup and record it here.
- `POST /api/reset` remains disabled in the cloud.

## 7. Security

| Control | How |
|---|---|
| Transport | Render-managed TLS; Neon requires TLS for JDBC |
| At rest | Neon encrypts storage and backups (provider-managed); broadcast this as provider encryption, not app-level encryption |
| Credentials | BCrypt hashes; HttpOnly + `SameSite=Lax` cookie, `Secure` added for cloud |
| Authorization | every read except `/api/stats` resolves a server-side session; writes are actor-checked |
| Secrets | env vars + GitHub secrets only; repo scan before submission |
| Database access | one least-privilege Neon role for the app; separate role/credential only if the backup job needs one |
| Request safety | JSON-only API, specific 400/401/403/402 bodies, no stack traces; security headers + body cap before go-live |
| Reset | fail-closed by default; no public admin path |

## 8. Monitoring and demo-day behaviour

- Render health check → `/actuator/health` (already exposes liveness/readiness groups).
- Render dashboard gives build/deploy logs plus CPU, memory and bandwidth metrics; Neon
  console shows CU-hours, storage and connections.
- Optional external uptime check (free tier) during the marking window.
- Cold start after 15 idle minutes is ~1 minute; open the URL before the panel, register
  one account, and keep the tab open. Do not run a 24/7 pinger: 750 instance-hours is
  almost exactly one month.

## 9. CI/CD

| Stage | Command | Gate |
|---|---|---|
| Frontend | `npm ci && npm run typecheck && npm run build` | type errors fail the run |
| Backend | `cd backend && ./mvnw -B verify` | 89 tests + JaCoCo |
| Contract | boot the jar, `node scripts/api-smoke.mjs` (no-reset mode) | 95 checks |
| Image | `docker build` | the container builds |
| Deploy | Render deploy hook on `main` | secret, not in repo |

## 10. Cost estimate (provisional — freeze after the live URL exists)

| Line | 100 users | 1,000 users | 10,000 users |
|---|---|---|---|
| Render compute | ₹0 (Free, 750 h) | ₹0 only if concurrency stays demo-scale; otherwise **$7/mo Starter** | $7/mo Starter (single instance is still not a real capacity plan) |
| Neon Postgres | ₹0 (Free, 1 GB / 100 CU-h) | ₹0 within the free allowance | paid tier — **verify the current price at signup** |
| Object storage | ₹0 (10 GB free bucket) | ₹0 (a few GB of backups) | storage cost ≈ $0.15/GB-month beyond the free bucket |
| GitHub Actions | ₹0 (public repo) | ₹0 | ₹0 |
| **Total** | **₹0** | **₹0–$7** | **≈ $7 + Neon paid tier** |

Assumptions: 100 users ≈ light demo traffic; 1,000 monthly users do not mean 1,000
concurrent users; the 0.1 CPU / 512 MB instance and the 1-second snapshot poll are the
binding constraints, not storage. These are *floor* costs, not a scaling claim.

## 11. Risks and open items

| Risk | Mitigation | Status |
|---|---|---|
| `PostgresStore` is the largest code change and can destabilise a working demo | build it behind the `Storage` interface, keep SQLite for tests, run the full suite + smoke against Neon before switching the deployed service | open |
| 512 MB / 0.1 CPU | `MaxRAMPercentage`, measure RSS, keep the jar/static small | unmeasured |
| 750 instance-hours | let it sleep; warm only during the marking window | planned |
| Neon 100 CU-hours / 1 GB | small pool (≤5), short idle timeout, monitor the console | planned |
| 5 GB Render bandwidth | no large media; the SPA is ~250 kB | planned |
| Cold start ~1 min | warm-up instruction + health check | known |
| Object storage provider undecided | Railway Bucket (10 GB, free, no card) or Supabase Storage; both need a new account | **open** |
| Free-tier policy change | re-check Render/Neon pages at signup and before each review | ongoing |
| Deep-link 404 / missing static bundle | items 4–5 in §4; verify with a real browser, not just curl | open |

## 12. Definition of done

- [ ] `PostgresStore` + schema + tests; existing suite green on both stores
- [ ] Dockerfile builds locally; container serves the UI on a non-8080 `PORT`
- [ ] Deep links (`/discover`, `/u/u_aarav`) survive a hard refresh
- [ ] Neon database seeded once; register → login → request → accept → review works
- [ ] Cookie has `Secure`; `/api/reset` answers `403`
- [ ] Public URL passes the no-reset smoke suite
- [ ] Nightly backup lands in the bucket and one restore is rehearsed
- [ ] Actions workflow green; deploy hook deploys `main`
- [ ] Cost table and monitoring screenshots frozen with real numbers
