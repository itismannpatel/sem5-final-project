# Twofold — Security Spot-Check

> **Purpose:** a record of the security probes actually run against the running application,
> what they showed, what was fixed, and what is knowingly out of scope. This is a
> *spot-check* for a local single-user demo, not a penetration test and not a claim of
> production readiness — the brief asks for a legal/security section, and an honest small
> probe is worth more than a paragraph of aspiration.
>
> **Update 2026-11-24:** the "no authentication" finding below was **fixed** by Task 2
> (BCrypt credentials, HttpOnly session cookies, `401`/`403` enforcement — see
> `docs/API.md`). The probe text is left as it was recorded, with this note, so the
> before/after is honest; read endpoints were deliberately left public (documented in
> `TEST_SUMMARY.md` §7, including the demo-email exposure). Re-probe items still open:
> rate limiting, CSRF token (mitigated by `SameSite=Lax`), security headers, body-size cap.
>
> **Date:** 22 November 2026 · **Target:** packaged jar
> (`twofold-backend-0.0.1-SNAPSHOT`, Spring Boot 4.2 / Java 21) on `:8080`, frontend on
> `:5173` via the Vite proxy · **Method:** manual `curl` probes + code review of the
> request path · **Tester:** project team

---

## 1. Scope and threat model

**In scope:** the HTTP surface (`/api/*`), JSON parsing, data returned to the browser,
and the payment sandbox.

**Out of scope, by design:** real authentication, multi-tenant authorisation, TLS
termination, infrastructure hardening and DDoS protection. This build is a course project
that runs on one machine with one selected demo identity; there is no account system to
break into, so "can a user read another user's data" is answerable only in the trivial
sense (yes — every client sees the full snapshot, which is the product design).

**Threats considered:** injection through paths/query/JSON; cross-site scripting via stored
content; method/route confusion; path traversal; accidental secret exposure (card tokens);
oversized payloads; information leakage through actuator endpoints; browser-side
hardening headers.

---

## 2. Results

| # | Probe | Result | Assessment |
|---|---|---|---|
| 1 | SQL-injection-shaped strings in query values (`?userId=' OR 1=1--`) | **400** (unknown user) | ✅ No SQL exists — the store is a JSON file, and no query string is built from input |
| 2 | Injection-shaped path segments (`/api/users/../../etc/passwd`) | **400** (Tomcat rejects the traversal) | ✅ No filesystem path is derived from the URL |
| 3 | `__proto__` / `constructor.prototype` keys in a JSON body | Ignored; request processed normally | ✅ Jackson maps to records; no prototype chain to pollute |
| 4 | Stored HTML/JS in a profile bio or message, then read back | Returned as **JSON string**, rendered as text | ✅ React escapes by default; `grep` confirms **no `dangerouslySetInnerHTML`** in `src/` |
| 5 | `PUT`, `TRACE`, `DELETE` on `/api/state` | **405** | ✅ Router dispatches on method + path only |
| 6 | Unknown route `/api/nope`, `/api/state/extra` | **404** | ✅ No catch-all that leaks internals |
| 7 | `Origin: https://evil.example` request | No `Access-Control-Allow-*` headers | ✅ No CORS policy is declared, so browsers block cross-origin reads |
| 8 | `/actuator/env`, `/actuator/heapdump`, `/actuator/configprops` | **404** | ✅ Only `health` (and `info`) exposed |
| 9 | Oversized body (3 MB JSON) | **201 accepted** | ⚠️ No max-body cap — hardening item (§4) |
| 10 | Response headers (CSP, HSTS, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`) | None set | ⚠️ Hardening item (§4); low risk locally, required before any public deploy |
| 11 | `POST /api/reset` without any credential | **200**, seed restored | ⚠️ Accepted: a public reset is a deliberate demo affordance |
| 12 | Brute force / repeated calls | No rate limiting, no lockout | ⚠️ Accepted for local demo |
| 13 | Card token in the ledger after a purchase | `****9876` (masked to last four) | ✅ Never stores the presented token |
| 14 | Card token in a **declined** response body | Originally echoed in full — **fixed** | 🔴 → ✅ `DEF-013` |
| 15 | Failed payment side effects | Version unchanged, no ledger row written | ✅ A decline leaves no trace to reconcile |

### 2.1 The one real finding — `DEF-013`

A declined sandbox charge returned
`{"error":"payment failed: card tok_decline_4242 was declined (sandbox)"}`: the raw token,
back to the client. The ledger was already masked, so this was the single path that leaked
a credential — harmless with a fake token, wrong as a habit, and error bodies end up in
logs, screenshots and support tickets.

**Fix:** `SandboxGateway` now formats the message with `PaymentService.mask(...)`, the same
helper the ledger uses. **Verification:** `PaymentServiceTest` asserts the message contains
`****4242` and *not* the raw token; a live probe through the Vite proxy returns
`payment failed: card ****4242 was declined (sandbox)`. Full entry in `DEFECT_LOG.md`.

---

## 3. What the probes did **not** find

Worth stating explicitly, because absence of findings is information too:

- No injection sink: there is no SQL, no shell, no template evaluation and no dynamic
  file path in the request path. The only I/O is the JSON store at a fixed path.
- No authorisation bug *relative to the design*: the app is intentionally
  "one shared workspace, one selected identity". Authorisation would be a product change,
  not a patch.
- No secret in the bundle: the frontend holds no keys; the sandbox gateway is local and
  keyless by design (that is why the e-commerce deliverable can be demoed with no signup).
- No crash or stack trace leaked to the client: errors are `{ "error": "..." }` with a
  human message; unexpected exceptions are logged server-side and returned as 500.

---

## 4. Hardening backlog (pre-deploy, not done here)

Ordered by value for the cloud phase; each is small and independent.

1. **Security headers** — a `OncePerRequestFilter` adding `X-Content-Type-Options: nosniff`,
   `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, and a CSP once the asset list is
   fixed. Cheapest real win.
2. **Body size cap** — `server.tomcat.max-swallow-size` / a `Content-Length` check in the
   controller; 1 MB is plenty for this API.
3. **Rate limit + reset guard** — a simple token bucket on `/api/*`, and gate
   `/api/reset` behind an env-var flag so a deployed instance cannot be wiped anonymously.
4. **Real auth** if it ever ships — the identity picker is `localStorage` only
   (`twofold.auth.v1`) and documented as *not* authentication.
5. **TLS** — terminate at the host (Render/Fly provide it); never serve the app over plain
   HTTP beyond localhost.

The cloud plan (`docs/cloud/DEPLOYMENT_PLAN.md`) references this backlog as a pre-deploy
gate.

---

## 5. Conclusion

For the artefact it is — a local, single-user teaching demo with a sandbox payment
simulator — the surface is small and the probes found **one** real issue, which is fixed
and regression-tested. Nothing here should be read as "secure enough for real users"; the
honest claim is **"no injection, no stored XSS, no token retention, no actuator exposure,
and a documented list of what production would still need"**.
