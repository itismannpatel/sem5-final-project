# Twofold — Business Documentation Playbook (Sem 5 Integrated Assignment)

> **Who this file is for:** the teammate owning the **business / startup / e-commerce**
> writing. It is self-contained — you do **not** need the code or the AI agent's
> memory to use it. Read this top to bottom once, then work down the deliverables
> list in §6.
>
> **Who owns what:** the engineering team owns code, testing and cloud. You own the
> business documents, market research, canvases, funding/compliance notes and the
> pitch deck content. The final report and demo video are shared — see §13.
>
> **Source of truth:** the assignment brief (`Sem 5.pdf`, 9 pages) plus this file.
> Where this file and a blog disagree, the brief wins. Where this file and the live
> app disagree, **the app wins** — the documents must describe what actually exists.

---

## 1. What the brief actually demands (condensed, then quoted)

The brief asks the team to act as **founders of a startup**: pick a real problem,
design a business around it, build a working product, test it, deploy it on a public
cloud, and pitch it to a panel acting as **investors**. Grading is across five
subjects — code alone is not enough.

Two subjects are yours:

**Startup Policies — the brief's exact words**
> "Lean Canvas and Business Model Canvas, SWOT and PESTEL analysis, MVP plan,
> funding plan, DPIIT recognition readiness, IP and compliance note, pitch deck"

**E-Commerce — the brief's exact words**
> "Business model and revenue streams, store or platform flow, payment approach,
> logistics or delivery plan, digital marketing plan, legal and security checklist
> (privacy, consent, fake reviews, dark patterns)"

Plus two cross-subject obligations that land on you:

- **Cloud:** "Cost estimate: estimated monthly cloud cost at 100, 1,000 and 10,000
  users, **linked to your funding plan**." (§4 of the brief)
- **Testing:** "a short **usability check with real users**" and the brief's strong
  project criterion: "**Evidence that real users want the solution**." You will run
  the user interviews for this (coordinate with the QA owner — see §13).

**The brief's five topic-suitability tests (brief §2)** — the proposal must answer all
five, and it is worth putting this checklist in the proposal itself:

1. Solves a real problem for a **clearly defined** group of users.
2. Works as an **online business** (store, marketplace, subscription service or
   digital platform) with a way to earn revenue.
3. Is suitable for **public-cloud deployment**, with a clear reason why the cloud
   helps (scalability, availability, reach).
4. Has **clear features and expected behaviour that can be tested systematically**.
5. Is **achievable as a working prototype** in the time given.

**Rules that constrain the writing (brief §8):** original work with credits for
anything borrowed; only permitted data; no personal data without consent; **payments
in test mode only**; honest reporting (no invented results); free tier / student
credits only, with budget alerts and shutdown after demos; **never put passwords,
API keys or tokens in the repo**; keep work in a shared version-controlled repo;
attend mentor reviews.

**What earns marks (brief §9):** a clear, specific problem; evidence real users want
it; clean design that handles errors; tests that find real bugs; a secure, sensibly
scaled, affordable cloud design with a reason for every service; **honest evaluation
including what did not work**; a convincing, realistic startup plan.

> ⚠️ The fastest way to lose marks here is a beautiful deck full of numbers nobody can
> trace. Every figure must be either (a) measured from the product, (b) cited to a
> source you actually opened, or (c) clearly labelled **assumption**. No exceptions.

---

## 2. The product you are writing about (fact sheet — do not drift from it)

**Twofold** — a peer-to-peer **skill exchange for college students**. Students list
what they can teach and what they want to learn; Twofold ranks the people who can
teach them, they book a session, meet (online or on campus), and review each other.
The core loop is **barter** — teach to learn — with an optional paid tier.

Tagline: **"Trade what you know for what you wish you knew."**

### The problem
College students already have valuable skills and a campus full of people who want
them, but the discovery layer is broken: notices on WhatsApp groups, club posters,
seniors who happen to know the right person. Paid courses are expensive and generic;
free content has no accountability. There is no trusted, campus-local way to find
someone who can teach a specific skill *and* who wants something you can offer.

### The product, feature by feature (this is what exists and is testable)
| Feature | What it does |
|---|---|
| **Skill profile** | Every student lists skills they *teach* and *want*, each with a level (Beginner / Intermediate / Advanced), availability slots, and preference: Online / On campus / Either |
| **Matching engine** | Scores every candidate out of 100: **Learn 45** (their skills on your wishlist) + **Reciprocity 35** (your skills on theirs) + **Affinity 12** (shared subject areas) + **Trust 8** (their review average; new students get a neutral 60%). Learn/reciprocity saturate at two skills. Every score shows its breakdown — never a black box |
| **Discover** | Search, 8 subject categories, sort by match / rating / newest, "two-way only" filter |
| **Session request** | Pick a skill, a topic, up to 3 proposed time slots, optional note → the teacher accepts one slot, declines, or either side cancels; completing unlocks review |
| **Calendar export** | A confirmed session generates a real `.ics` file (Google / Apple / Outlook), no OAuth, no API keys |
| **Reviews & trust** | 1–5 stars + comment, only after a *completed* session, one review per author per session; the average feeds the Trust signal and the public profile |
| **Messaging** | Two-pane chat with unread counts, persisted across tabs/users |
| **Reminders** | Background job (7-day window) writes session-reminder records; exposed read-only for monitoring |
| **Twofold Pro (paid)** | Freemium tier: **₹99 / month** or **₹349 / semester (180 days)**, **sandbox payments in test mode only** |
| **Verified student badge** | Seed profiles carry verification; the concept is campus-email verification (not built as a real flow — say so) |

### Status you must state honestly (as of 2026-11-25)
- The app **runs locally** and is **fully tested**: 89 automated Java tests green,
  91.6% instruction / 78.7% branch coverage, 95-check API contract suite green,
  concurrency + load tested, 16 defects found and fixed.
- **Authentication is real and server-side** — BCrypt passwords, opaque server-side
  sessions, HttpOnly `SameSite=Lax` cookie. The 12 seeded students have no account and
  cannot sign in; the only way in is to register. No shared or demo password exists.
- **Cloud deployment is decided, not finished.** Render Free (Docker) + Neon Free
  Postgres + free object storage for backups + GitHub Actions; accounts exist and the
  design is frozen in `docs/CLOUD.md`. **Never write "live at …" or paste uptime
  screenshots you don't have.** Mark cloud-dependent numbers **provisional** and
  re-freeze them after deployment.
- **Payments are sandbox only.** No gateway account, no real money, no card data
  stored. The ledger stores a masked token (`****4242`). Say this explicitly — the
  brief requires test mode, and claiming real payments would be a lie.
- **Data:** 12 seeded demo students, 41 skills, 12 sessions, 14 reviews, 9 messages.
  These are **demo** numbers, not traction. If you need user numbers for the pitch,
  they must come from your own interviews/survey and be labelled as such.

### Technology, in one paragraph you may reuse
React 18 + TypeScript SPA (Vite) · Spring Boot 4.2 on Java 21 REST API · SQLite
database locally, managed Neon Postgres planned for cloud · sandbox payment gateway
behind an interface (`PaymentGateway`) · scheduled background reminder job. **Cloud
design decided but not built** (`docs/CLOUD.md`): containerised with Docker, CI via
GitHub Actions, free-tier Render (Singapore) + Neon Postgres + object storage for
backups, HTTPS by default, ₹0/month at prototype scale. Do not describe hosting in the
present tense until the live URL exists.

### Team (verify names/roles against the README before publishing)
Four members: Team/Product lead (also Java/API and backend), Frontend & UI-UX lead,
Matching & scheduling lead, QA & Chat lead — with cloud, marketing and documentation
shared. **Every member must
be able to explain any part in the viva**, so keep the documents in language the whole
team can defend.

---

## 3. Brand and writing standards (keeps six documents looking like one company)

- **Name:** always `Twofold` (lower-case wordmark styling `twofold.` only in the logo).
  Never "Two Fold", "2Fold", "Twofold app".
- **Voice:** calm, concrete, student-to-student. Short sentences. No hype adjectives
  ("revolutionary", "game-changing", "seamless", "AI-powered" unless true).
- **Audience for every document:** a faculty panel that has read the brief and an
  investor who has 10 minutes. Lead with the decision, then the evidence.
- **Palette (from the product's design tokens):** near-black canvas, **ember** for
  "what you teach", **mint** for "what you want to learn", warm off-white text.
  Use the app's screenshots rather than stock photos of handshakes.
- **Type:** Instrument Serif for headings, Inter for body (both are in the app).
  In Google Docs/Slides, `Playfair Display` / `Georgia` + `Inter` are acceptable
  substitutes; don't mix more than two families.
- **Charts:** one message per chart, labelled axes, units on the axis (₹, users,
  months), source line under every chart, no 3-D, no gradients.
- **Citations:** footnote or bracketed `[Source: URL, accessed 22 Nov 2026]`.
  A claim you cannot source goes in an "Assumptions" box, not in the body as fact.
- **Every document starts with:** title, team name, date, version, owner, and a
  3-line executive summary. Every document ends with **"Open questions / next
  steps"** — panels reward honesty and it gives you something to say in the viva.
- **Length discipline:** the brief never asks for length. Aim for the shortest
  document that answers every required element. A 4-page e-commerce doc with a real
  flow diagram beats 15 pages of prose.

---

## 4. File names, formats and where they live

Create these inside `docs/business/`. Keep the names stable — the final submission
pack references them.

| # | Deliverable | File | Format | Target length |
|---|---|---|---|---|
| 1 | Topic proposal & startup brief | `01-topic-proposal.md` (+ PDF) | 1 page | 1 page |
| 2 | Lean Canvas | `02-lean-canvas.md` (+ PNG export) | 1 page grid | 1 page |
| 3 | Business Model Canvas | `03-business-model-canvas.md` (+ PNG) | 1 page grid | 1 page |
| 4 | SWOT + TOWS | `04-swot.md` | 1 page | 1 page |
| 5 | PESTEL | `05-pestel.md` | 1–2 pages | 2 pages |
| 6 | MVP plan | `06-mvp-plan.md` | 2 pages | 2 pages |
| 7 | Funding plan | `07-funding-plan.md` | 2–3 pages | 3 pages |
| 8 | DPIIT recognition readiness | `08-dpiit-readiness.md` | 1–2 pages | 2 pages |
| 9 | IP & compliance note | `09-ip-compliance.md` | 1–2 pages | 2 pages |
| 10 | **E-commerce design document** | `10-ecommerce-design.md` (+ PDF) | 6–8 pages | main doc |
| 11 | Legal & security checklist | `11-legal-security-checklist.md` | 2 pages | checklist |
| 12 | Digital marketing plan | `12-marketing-plan.md` | 2 pages | 2 pages |
| 13 | Cost estimate 100 / 1k / 10k | `13-cost-estimate.md` + `cost-model.csv` | 2 pages + sheet | 2 pages |
| 14 | Pitch deck (official college format) | `PPT_CONTENT.md` → `Twofold-Final-Deck.pptx` | cover + Slides 1–16, 16:9 | 17 slides |
| 15 | Funding & compliance note (deck companion) | `15-funding-compliance-note.md` | 1–2 pages | 2 pages |
| 16 | Final report — business chapters | `16-report-business-chapters.md` | — | hand to report owner |
| 17 | Presentation & viva pack (deck script, Q&A bank, video storyboard) | `17-presentation-viva-pack.md` | 3–4 pages | 4 pages |
| 18 | Roles & contribution matrix | `18-roles-contribution.md` | 1 page | 1 page |

Also produce one **`00-package-index.md`** that lists every file, its status
(draft / reviewed / final), owner and date — this is what you hand over at the end.

Export rules: submit **PDF** for anything with a layout; keep the editable source in
the repo (Markdown, `.docx`, `.pptx`, or a Figma/Canva link in `00-package-index.md`).
Screenshots go in `docs/business/assets/` and must be of **our** app.

---

## 5. Tooling: what to use and what to avoid

**Use:**

| Need | Tool | Why |
|---|---|---|
| Writing / long docs | Google Docs (comments for review) or Markdown in the repo | Comment history = review evidence the brief likes |
| Canvases | Canva (Lean/BMC templates) or Figma / Excalidraw | One-page grids must fit on one page; export PNG at 2× |
| Diagrams (platform flow, revenue flow, ARCHITECTURE for cloud) | **draw.io / diagrams.net** or **Excalidraw**; Mermaid if you prefer text | Vector, editable, no license issues |
| Financial model + charts | **Google Sheets** (`cost-model.csv` lives next to it) | Formulas visible → numbers are traceable |
| Deck | Built by `scripts/build-deck.py` from `PPT_CONTENT.md`; open in PowerPoint, export PDF for submission | Official 16-slide format, 16:9, 10 × 5.625 in, speaker notes in the file |
| Screenshots | The running app (local), browser at 1440×900, crop to content | Real product beats mockups |
| Research | Google Scholar / AISHE / Startup India / RBI / official vendor pricing pages | Citable, current |
| AI chats | ChatGPT / Claude / Gemini / Perplexity (see §7) | Drafting and critique — **never sources** |
| Spelling / clarity | LanguageTool or Grammarly free | One pass, not a rewrite |
| Citations | Zotero (free) or a simple `Sources.md` | One list reused across all docs |

**Avoid:** paid templates with watermarks, stock "diverse team at laptop" photos,
icons from random sites (use the app's own icon set), any chart with made-up
precision, and any AI-generated citation or statistic.

**Never put in any document or repo:** passwords, API keys, card numbers, student
phone numbers/emails, or real names of interview participants without written
consent. Interview quotes are anonymised (`Participant A, 2nd year CSE`).

---

## 6. Deliverable-by-deliverable instructions

Work in this order — later documents reuse earlier decisions. Each item below gives
you the required content, the two or three things graders look for, the traps, and a
prompt you can paste into an AI chat.

### 6.1 Topic proposal & startup brief (1 page)

**Must contain:** problem statement in one sentence; who has the problem (specific:
"2nd–4th year students on Indian campuses", not "everyone"); the solution in three
sentences; why now; why cloud; revenue model in one line; team + roles; what the MVP
will and will not do; and a ticked line for each of the brief's five topic-suitability
tests (§1). Add `Approved by: <mentor> on <date>` at the top once the mentor signs off —
the brief's topic-selection phase requires an **approved** one-page proposal, and the
date is your evidence at review time.

**Grader looks for:** a *specific* problem and a *defined* user group (brief §2 and
§9). "Students struggle to learn skills" is too vague; "a 3rd-year CSE student who
wants Figma for a design internship has no way to find the design student two
hostels away who wants React help" is specific.

**Trap:** don't describe the dream product. Describe the prototype you are actually
building this semester.

> **Prompt:** "You are a startup advisor. Using only these facts [paste §2 fact
> sheet], write a one-page topic proposal for an Indian B.Tech integrated assignment.
> Sections: Problem, Target user, Solution, Why now, Why cloud, Revenue model (freemium
> ₹99/month or ₹349/semester, sandbox), MVP scope (in/out), Team & roles. Max 450
> words, no adjectives I can't defend, no invented market numbers."

### 6.2 Lean Canvas (9 blocks, one page)

Fill all nine, in this order: Problem (top 3), Customer segments, Unique value
proposition, Solution (top 3 features), Channels, Revenue streams, Cost structure,
Key metrics, Unfair advantage.

**Twofold seeds:** Problem = no trusted campus-local way to find a skill partner;
paid courses are expensive/generic; existing peers are invisible. Segments = students
who want internship-relevant skills; students who want to teach and build credibility;
campus clubs as a channel. UVP = "the only skill swap that only works when both sides
gain — your match score *is* the barter". Channels = campus clubs, WhatsApp/Instagram
student groups, seniors, notice boards with QR codes, one workshop per club. Revenue =
Pro subscription (₹99/₹349) + later campus partnerships/sponsored tracks. Costs =
hosting (₹0 free tier at prototype scale), domain, design/marketing spend, time.
Metrics = weekly active swappers, sessions completed per week, two-way match rate,
Pro conversion, review rate. Unfair advantage = verified campus graph + reciprocity
data nobody else has.

**Trap:** "Unfair advantage" is not "our matching algorithm" (copyable). It is the
campus trust graph and club distribution.

> **Prompt:** "Fill a Lean Canvas for this product [facts]. Return a 9-row table:
> block, one-paragraph content, and the single number/metric attached to it. Flag any
> block where you had to assume something."

### 6.3 Business Model Canvas (9 blocks, one page)

Key partners (colleges/clubs, cloud provider, payment gateway in test mode),
key activities (matching, moderation, campus activation), key resources (product,
campus graph, brand), value propositions (per segment — students who teach vs who
learn), customer relationships (self-serve, community, reviews), channels, customer
segments, cost structure (fixed vs variable, itemised), revenue streams (subscription,
future partnerships). Keep it consistent with §6.2 — same numbers, same segment names.

**Trap:** BMC and Lean Canvas contradicting each other is the most common lost mark.
Build BMC *from* the Lean Canvas, then diff them line by line.

### 6.4 SWOT + TOWS

Four quadrants, 4–6 bullets each, each bullet one line. Then a short **TOWS** section
(SO/WO/ST/WT strategies) — that is what turns a school SWOT into a strategy.

**Twofold-specific honesty:** Weaknesses include auth without password reset, email
verification or rate limiting, a single-city/single-campus cold-start problem,
free-tier cold starts (~1 min wake), and no mobile app. Threats include LinkedIn
Learning/Udemy bundles, campus clubs doing this offline, privacy regulation, and the
free-tier limits (Render free instance hours, Neon CU-hours) documented in
`docs/CLOUD.md`.

**Trap:** generic strengths ("good team, innovative idea"). Use only strengths a
grader could verify from the product.

### 6.5 PESTEL

Six sections (Political, Economic, Social, Technological, Environmental, Legal), each
with 2–3 India-specific factors and the **implication for Twofold**. Legal must cover
the DPDP Act 2023 (consent, purpose limitation, breach reporting), IT Act/intermediary
considerations for user-generated content, and consumer-protection e-commerce rules if
you call it a marketplace. Environmental can be short but real: cloud region choice,
energy per request, no shipping.

**Trap:** copying a PESTEL from a blog. Every factor needs a source or an
"assumption" label, and every factor needs a consequence ("→ we will …").

### 6.6 MVP plan

Include: MVP definition (one paragraph), **in-scope** feature list with acceptance
criteria, **out-of-scope** list with the reason, milestone timeline aligned to the
brief's phases (Review 1, Review 2, launch), success metrics with target numbers,
validation plan (interviews + usability test), risks + mitigations, and a
build/measure/learn loop. Map each in-scope feature to something the app actually
does today — the team can prove it live.

**Trap:** claiming an MVP feature that isn't built. If it's planned, put it in
"post-MVP".

### 6.7 Funding plan

Structure: bootstrapped status → seed requirement → use of funds (table with % and
₹) → 12–24 month milestones → unit economics → revenue projections (conservative /
base / optimistic) → funding ask and instrument (equity vs grant/competition prize) →
runway table → assumptions and sensitivities.

Use the **same cost model** as §6.13. At prototype scale infrastructure is ₹0 on free
tiers; the real costs are people, design, marketing and a domain (~₹1,000/year).
Be explicit that the free tier is a *student* constraint and what it would cost to
serve real users (see §6.13).

Unit economics you can defend: ARPU = ₹99/month or ₹349/6 months (₹58/month
equivalent); gross margin ≈ 98% (no per-transaction infrastructure cost at this
scale); CAC assumed from campus activation cost per acquired student; LTV with a
stated churn assumption. **Label CAC/LTV/churn as assumptions** until you have data.

**Trap:** a ₹5-crore ask with no milestone attached. Ask for what a semester of
execution costs, and say what the money buys.

### 6.8 DPIIT recognition readiness

Explain what Startup India / DPIIT recognition is, who is eligible, the documents
needed (entity registration, PAN, a short innovation write-up, etc.), the application
path (Startup India portal), what it unlocks (self-certification, procurement
reliefs, tax exemptions subject to conditions, easier compliance), and our current
readiness: **not yet incorporated → not yet eligible**. Then list the concrete steps
and owner to become ready. Include a "not legal advice" line.

**Trap:** stating benefits as guaranteed. Tax exemptions (e.g. 80-IAC) are
inter-ministerial and conditional — verify on official pages, cite them, and
date-stamp the claim.

### 6.9 IP & compliance note

Cover: own work + third-party credits (React MIT, Spring Boot Apache-2.0, Vite MIT,
fonts under their OFL/Apache licences — verify in `package.json`/`backend/pom.xml`);
trademark intent for "Twofold" (class 41/42, to be filed, not filed); copyright in
the code and content; data-protection posture (DPDP Act: consent, notice, purpose
limitation, retention, deletion, grievance contact); academic-integrity statement
(original work, AI used as a drafting tool, sources cited); open-source licence
obligations (attribution/NOTICE file); and no-real-payments statement.

**Trap:** claiming a registered trademark or a filed patent. Use "intended",
"planned", "not yet filed".

### 6.10 E-commerce design document (the main one, 6–8 pages)

Required sections, in this order:

1. **Executive summary** (½ page) — what the business is, who pays, what they pay
   for, current stage.
2. **Business model & revenue streams** — freemium two-sided marketplace; barter is
   free forever, Pro is the paid tier; a table of revenue streams (now / next /
   later) with price, payer, and status (live in sandbox / planned).
3. **Store / platform flow** — the actual customer journeys, as a diagram **and**
   numbered steps. Two things graders look for and this product must state plainly:
   **there is no cart** (a single digital subscription SKU is bought directly, so a
   cart/checkout-page metaphor would be dishonest), and the ledger view is the
   **order-history** equivalent — every captured payment appears with plan, amount,
   masked token, status and date:
   - *Learner:* sign in → profile wishlist → matches ranked → request session →
     teacher accepts → meet → complete → review.
   - *Teacher:* profile skills → request arrives → accept/decline → session →
     review.
   - *Buyer:* profile → Twofold Pro → choose plan → sandbox checkout → Pro active →
     ledger entry.
   - *Payment failure:* declined token → HTTP 402 → no charge, no ledger row, user
     sees a clear error → retry. (This error path is a graded requirement — describe
     it explicitly.)
   Then a one-paragraph **competitive snapshot**: the alternatives a student uses
   today (campus clubs/friends, YouTube/free content, LinkedIn Learning, Udemy,
   Coursera, college placement cells) and the one axis where Twofold differs
   (reciprocity + campus-local trust). Cite competitor prices you actually looked up;
   otherwise write "price not verified".
   Draw it in draw.io/Excalidraw with one swimlane per actor. Keep the diagram
   readable at A4.
4. **Payment approach (test mode only)** — architecture: checkout UI → API →
   `PaymentGateway` interface → `SandboxGateway`; token-based, no card numbers ever
   stored or logged (only a masked token `****4242`); success → 201 + ledger row;
   decline → 402 + specific error, no state change; idempotency and the "a failed
   write never bumps state" rule; what changes for a real gateway later (Razorpay/
   Stripe test keys, webhooks, PCI scope stays out of our servers). **State clearly:
   no real transactions, ever, in this project.**
5. **Logistics / delivery plan (digital service)** — there is no shipping. Define
   service delivery: instant access on purchase, session fulfilment by a human
   teacher, the match/scheduling loop as the "fulfilment SLA", reminder job as the
   no-show reducer, refunds/cancellations policy (session cancelled → no review, Pro
   refund policy stated), support channel and response target, and the fallback when
   the free-tier host is cold (retry/warm-up note). The brief explicitly allows
   "logistics **or** delivery" — make the digital-delivery argument explicitly, or a
   grader will look for a courier plan.
6. **Digital marketing plan** — target segments, positioning statement, channel plan
   (campus clubs, class WhatsApp groups, Instagram reels of real swaps, LinkedIn for
   placement-prep angle, posters with QR, one launch event per campus), content
   calendar for 8 weeks, a referral loop ("both sides get a free Pro week" —
   labelled as planned), metrics (signups, activation = first session requested,
   two-way match rate, Pro conversion), budget (₹0 organic + a small paid test), and
   how you will measure it (UTM links, weekly dashboard). Tie one campaign to the
   demo date.
7. **Legal & security checklist** — the table in §6.11, summarised with statuses.
8. **Open issues** — auth not built, payments sandbox, single-campus cold start,
   moderation policy not enforced, data-retention period undecided.

**Grader looks for:** all six e-commerce elements present and *specific to this
product*; a real flow diagram; the failed-payment path; test-mode honesty; and the
dark-pattern section actually answered (see §6.11).

### 6.11 Legal & security checklist (a table, two pages)

One row per control: `#`, area, requirement, how Twofold satisfies it *today*, status
(`done` / `partial` / `planned` / `not applicable`), evidence link, owner. Include a
**budget & resource-hygiene** block from brief §8: free tier only, no card on file,
budget alert / spend limit set, faculty-approved limits, and resources shut down or
deleted after each review and the final demo (with who does it and when). Areas:
privacy notice & consent; data minimisation (no phone/address/payment data);
retention & deletion; access control (BCrypt credentials, server-side sessions, HttpOnly
cookie; rate limiting and a CSRF token are the known gaps); transport security (HTTPS on the host); secrets management (env vars, none
in repo — the repo is scanned); input validation & error handling (server returns
specific 400s, never stack traces); injection/XSS posture (JSON API, React escapes
output, no `innerHTML`); dependency licences; payments in test mode; moderation &
reporting of abusive content (**planned**); **fake reviews** (one review per
completed session per author, no purchase of reviews, no incentives — say how it is
enforced); **dark patterns** (no pre-ticked consent, no hidden charges, cancel is as
easy as subscribe, no fake urgency/scarcity, no confirm-shaming — list each avoided
pattern); accessibility (keyboard, focus states, reduced-motion support); breach
response contact.

**Trap:** writing "we are secure" with no evidence column. Link to the actual test
(`docs/testing/SECURITY_SPOT_CHECK.md` — 15 probes, already written) and to code paths.

### 6.12 Digital marketing plan (if you split it out of 6.10)

Same content as §6.10 item 6, expanded with an 8-week calendar table, two sample
Instagram captions, one poster copy block, one club-partnership email template, and
the measurement plan. Keep every claim runnable by a four-person student team with
₹0–₹2,000.

### 6.13 Cost estimate at 100 / 1,000 / 10,000 users (cloud marks + funding link)

Method: **driver-based**, not guessed totals.

1. Define drivers: monthly active users, requests per user per month, average
   response size, storage per user, DB compute hours.
2. Price each driver from the verified free-tier research
   (`docs/CLOUD.md`; the working research in the ignored `docs/cloud/` is dated
   22–25 Nov 2026) and vendor pages.
3. Produce a table: `line item · 100 users · 1,000 · 10,000 · assumption/source`.
4. Separate **₹0 free-tier reality** (prototype: Render Free 0.1 CPU/512 MB, Neon
   Free 100 CU-hours, GitHub Actions free on a public repo) from **what it costs when
   the free tier stops being enough** (paid instance, DB compute/storage, egress),
   and say at which user count each limit breaks.
5. Add non-cloud costs (domain, email, design tools) and the **people cost** line,
   because a funding plan that ignores salaries is not credible.
6. End with a sensitivity note: "if requests/user double, cost scales ~2× on compute;
   if storage passes 1 GB, Neon blocks writes rather than charging."

Keep the spreadsheet (`cost-model.csv`) in the repo so every number in the deck and
the funding plan traces to a cell. **Freeze the numbers only after deployment**, then
update §6.7, §6.10 and the deck in one pass.

### 6.14 Pitch deck (the official 16-slide college format)

**The template wins over any deck advice in this file.** `Ecomm-Startup ppt structure.pptx`
(17 slides: cover + Slides 1–16, 10 × 5.625 in) is the verified format the college expects.
Do **not** invent a different slide order, do not add appendix slides to the submitted
file, and do not merge two template slides into one. Content lives in `PPT_CONTENT.md`;
`python scripts/build-deck.py` builds `Twofold-Final-Deck.pptx` from it, so edit the
markdown and rebuild rather than editing the deck.

| Slide | Template title | Our content (one line) |
|---|---|---|
| 1 | Business Idea and Introduction | Marketplace where students trade skills instead of paying for lessons |
| 2 | Problem Identification and Idea Generation | Siloed campus skills, unaffordable tutoring, the JTBD sentence |
| 3 | Market Research and Opportunity Analysis | Target students, competitor table, SWOT, differentiation |
| 4 | Business Model Canvas | Nine blocks, supply and demand are the same person |
| 5 | Business Model and Revenue Strategy | C2C marketplace, free barter, Pro ₹99 / ₹349 |
| 6 | Product Development and Validation | The MVP, its test evidence, feedback and the fixes it caused |
| 7 | Building the Startup Team | Four people covering technology, marketing/sales, operations, finance |
| 8 | Legal Structure, Registration and Policies | Pvt Ltd at first revenue, DPIIT, trademark, four policies |
| 9 | E-Commerce Website or Online Store Setup | Custom storefront, skill profiles as catalogue, no cart by design |
| 10 | Payment, Logistics and Supply Chain | Sandbox gateway, digital fulfilment, calendar as the supply chain |
| 11 | Digital Marketing and Customer Acquisition | Campus density, ambassadors, Reels, long-tail SEO |
| 12 | Customer Experience and Retention | Review gating, in-app support, reciprocity as the loyalty loop |
| 13 | Financial Planning and Startup Resources | ₹0 infra, ₹15k/semester marketing, break-even at 7 Pro subscribers |
| 14 | Launch Plan and Growth Strategy | One campus → five → placement season, with activation gates |
| 15 | Risks, Ethics and Sustainability | Five risks with owners, privacy by minimal data, digital footprint |
| 16 | Conclusion and Future Vision | The swap loop, the reciprocity graph, the value sentence |

Rules for writing each slide: answer every prompt printed in the template's own notes
(split them across the body and the speaker notes), keep the body to six short bullets,
and put the evidence in the notes. The template's "Note:" lines (legal caveat, stated
assumptions, "explain which channels and why") are grading cues — answer them on the
slide in plainer words.

Speaker notes carry the spoken script (60–90 seconds per slide) plus the template
prompts that slide answers. Engineering depth — architecture, API, concurrency, test
cases, cloud operations — stays **out** of the 16 slides; the archived 59-slide version
is `docs/presentation/deep-dive-deck-content.md`, used only if the panel asks for depth.

### 6.15 Funding & compliance note (deck companion, 1–2 pages)

Two halves: (a) the ask — amount, instrument, use of funds, milestones, runway;
(b) compliance — entity status, DPDP posture, test-mode payments, IP/OSS credits,
budget alert + shutdown policy, and the DPIIT readiness line. One page each, no new
numbers that contradict §6.7.

### 6.16 Final report — business chapters

The report is one document owned by the team lead; you supply the chapters the
brief expects from your subjects: startup context and problem validation, business
model and revenue, e-commerce platform design + payment approach, market and
competition, go-to-market, funding ask and unit economics, compliance posture, and a
short "what we learned / what did not work" section. Reuse the finished documents
rather than rewriting; keep every figure identical to §6.7 and §6.13 and cite the
source documents by name. Target 8–12 pages for your chapters, written so a reader
who never saw the deck still understands the business.

### 6.17 Presentation & viva pack

The brief's final submission is "a 5-minute demo video **followed by
presentation/viva**", and the intro lists **Presentation Skills** as a graded subject.
Produce:

1. **10-minute live pitch script** — one block per slide, spoken lines (not bullet
   points), timing marks at 2/5/8 minutes, and who says what (split across 3–4
   speakers; every member speaks).
2. **5-minute demo video storyboard** — shot list tied to the app: landing → sign in
   as Aarav → Discover filters → match breakdown → request a session → accept as the
   teacher → complete → review → Pro checkout with a **declined** card then a
   successful one. Include the screen-recording settings, the exact clicks, the
   voice-over script per shot, and a backup recording plan (the app runs locally, so
   record locally if the free-tier host is cold).
3. **Q&A bank** — 20 likely panel questions with short, honest answers: why this
   market, why barter plus subscription, why not real payments yet, what happens at
   10× users, how you handle fake reviews, what your tests actually prove, what failed,
   what the free tier costs, what you would do with funding. Every answer must match
   the documents — no new numbers.
4. **One-page numbers cheat sheet** — prices, seed/demo scale, test counts, coverage,
   latency percentiles, cost at 100/1k/10k, the ask. Every speaker memorises it.
5. **Demo-day checklist** — laptop charged, backend started, seed reset, two browser
   profiles signed in as different students, offline screenshots as fallback, timer
   visible, deck PDF on a USB drive.

### 6.18 Roles & contribution matrix

Brief §6 requires roles assigned at the start, **every member contributing technical
work in at least one area**, and every member able to explain any part in the viva. A
one-page table: member · primary role · technical contribution (with the artefact it
produced) · documents owned · review attended (date). This protects the team: if one
person appears to have only written documents, the panel will ask what technical work
they did, so pair the writing with one real technical deliverable (e.g. the test
cases, the cost model scripts, or the deployment checklist).

---

## 7. How to use AI chats without losing marks

**The rule:** AI drafts, *you* verify. Every number, citation, legal claim and
competitor fact gets checked by a human against a page you actually opened. Fabricated
citations are the single most common way AI-assisted assignments fail.

**Working method for every document:**

1. **Context message** — paste §2 fact sheet + the relevant §6 instructions + the
   target length + "mark anything you assume with [ASSUMPTION]".
2. **Outline first** — ask for a heading outline only; approve it before prose.
3. **Draft section by section** — one section per message; long single-shot drafts
   drift and hallucinate.
4. **Adversarial pass** — "critique this as a faculty examiner using the brief's
   criteria; list every unsupported claim, every vague statement, every contradiction
   with the fact sheet."
5. **Compress** — "cut 30% without losing any required element."
6. **Humanise** — rewrite the opening and closing yourself; panels notice AI cadence
   but reward clear thinking. Keep the team's own voice.

**Standing system prompt to reuse:**

```
You are helping a 5-person B.Tech student team write the business documentation for
their integrated assignment. The product is Twofold, a peer-to-peer skill exchange
for college students in India: students list skills they teach and want, get matched
by a 100-point reciprocity score, book sessions, and review each other. Revenue is a
freemium subscription: Twofold Pro at ₹99/month or ₹349/semester, currently sandbox
(test mode) only. The app is built and tested; cloud deployment (Render + Neon) is decided but not yet
live.

Hard rules:
- Use only the facts I give you. Never invent statistics, market sizes, citations,
  competitor claims, user counts or legal conclusions.
- If a number would strengthen the argument but I did not provide it, insert
  [NEEDS SOURCE] and say what to look up.
- Label every assumption [ASSUMPTION].
- Write for an Indian faculty panel and a time-pressed investor: specific, plain,
  no hype adjectives, no em-dashes in headings.
- Keep Twofold's honesty constraints: no real payments; authentication is real but has
  no password reset, email verification or rate limiting; no claimed traction.
Ask me for missing inputs before drafting if a required section would be guesswork.
```

**Research prompts** (use Perplexity or Google, not a chat model, for facts):
Indian higher-education enrolment (AISHE reports), student spending on online
learning, competitor pricing (LinkedIn Learning, Udemy, Coursera, campus clubs),
DPDP Act 2023 text, Startup India/DPIIT eligibility, GST threshold for services,
Render/Neon current pricing. Save each source URL + access date in `Sources.md`.

---

## 8. Evidence you must collect yourself (the brief grades this)

The brief rewards "evidence that real users want the solution" and a usability check
with **real users**. Plan one afternoon:

- **5 short interviews** (8–10 min) with students outside your team: current way of
  finding a skill partner, what they last tried to learn, what they would pay for,
  what would stop them. Record answers anonymously; get verbal consent to quote.
- **1 usability session** (15 min) with 2–3 students: give them the running app, ask
  them to find a React teacher and request a session, note where they hesitate.
  Hand the raw notes to the QA owner for `docs/testing/USABILITY_CHECKLIST.md`.
- **A 6-question survey** (Google Forms) in two class groups for a slightly larger
  sample. Report n honestly (e.g. "n = 23, convenience sample").
- Save anonymised notes in `docs/business/research/` and cite them in the deck's
  traction slide and in §6.6's validation section. **Never fabricate a percentage.**

---

## 9. Two-way traceability: brief → document

Before submitting, walk this table and point at the page/paragraph that satisfies
each line. Any empty row is a lost mark.

| Brief requirement | Where it is answered |
|---|---|
| Lean Canvas | `02-lean-canvas.md` |
| Business Model Canvas | `03-business-model-canvas.md` |
| SWOT | `04-swot.md` (+TOWS) |
| PESTEL | `05-pestel.md` |
| MVP plan | `06-mvp-plan.md` |
| Funding plan | `07-funding-plan.md` |
| DPIIT recognition readiness | `08-dpiit-readiness.md` |
| IP & compliance note | `09-ip-compliance.md`, `15-funding-compliance-note.md` |
| Pitch deck | `PPT_CONTENT.md` → `Twofold-Final-Deck.pptx` (official 16-slide format) |
| Business model & revenue streams | `10-ecommerce-design.md` §2 |
| Store / platform flow | `10-ecommerce-design.md` §3 + diagram |
| Payment approach (test mode) | `10-ecommerce-design.md` §4 |
| Logistics / delivery plan | `10-ecommerce-design.md` §5 |
| Digital marketing plan | `12-marketing-plan.md` / `10-…` §6 |
| Legal & security checklist | `11-legal-security-checklist.md` |
| Privacy, consent | checklist rows 1–4; IP note |
| Fake reviews | checklist row "reviews"; §6.11 |
| Dark patterns | checklist row "dark patterns"; §6.11 |
| Cost at 100 / 1k / 10k + funding link | `13-cost-estimate.md`, `07-funding-plan.md` |
| Five topic-suitability tests | `01-topic-proposal.md` checklist |
| Roles assigned; every member contributes technically | `18-roles-contribution.md` |
| 5-minute demo video + presentation/viva | `17-presentation-viva-pack.md` |
| Budget alert, free tier, shutdown after demos | `11-legal-security-checklist.md` budget block |
| Evidence real users want it | §8 research notes + deck slide 6 (validation) |
| Honest evaluation incl. what didn't work | every doc's "open issues"; deck slides 6 and 15 |
| Original work / credits | `Sources.md` + IP note |

---

## 10. Review process (this is graded too)

1. **Self-check:** the §9 table + the "trap" line under each §6 item.
2. **Peer review:** one teammate who did *not* write the document reads it and marks
   every sentence they cannot verify or explain. Log the review date in
   `00-package-index.md`.
3. **Consistency pass:** prices (₹99 / ₹349), plan names, feature names, seed counts,
   coverage numbers and cost figures must be identical across all documents and the
   deck. Keep a single `facts.md` (copy of §2 + frozen numbers) and diff against it.
4. **Mentor review:** bring the printed canvases + e-commerce doc + deck. Note the
   feedback and what you changed — the brief checks that reviews were attended.
5. **Freeze:** mark version `1.0` only after the cloud cost numbers are final.

---

## 11. Timeline (fit it around the coding/cloud work)

| When | Deliverable | Depends on |
|---|---|---|
| Day 1 | §6.18 roles matrix, §6.1 proposal (mentor approval) | fact sheet + team answers |
| Day 1–2 | §6.4 SWOT, §6.5 PESTEL | fact sheet only |
| Day 2–3 | §6.2 Lean Canvas, §6.3 BMC, §6.6 MVP | proposal decisions |
| Day 3–4 | §6.10 e-commerce doc §1–5, flow diagram | product walkthrough (run the app) |
| Day 4–5 | §6.11 checklist, §6.12 marketing | interviews start |
| Day 5–6 | §6.7 funding, §6.13 cost model (provisional) | cloud design decided (`docs/CLOUD.md`) |
| Day 6–7 | §6.8 DPIIT, §6.9 IP/compliance | entity status answer from team |
| Day 7–8 | §6.14 deck, §6.15 companion note, §6.16 report chapters, §6.17 presentation/viva pack | all of the above |
| After deployment | Re-freeze cost + funding numbers, add real health/metrics screenshots | live URL |
| Final | §6.16 report chapters, §9 traceability table, package index | everything |

**Do not** wait for the deployment to start writing — write with `[PROVISIONAL]`
markers, then do one freeze pass. Panels penalise empty sections far more than
provisional numbers that were later confirmed.

---

## 12. Common failure modes (check yourself against these)

- Vague problem ("students need skills") — fix with a named persona and a concrete
  moment.
- Canvases that contradict each other or the pitch deck.
- Revenue described but no payer, no price, or a price that appears nowhere in the
  product (the app sells ₹99/month and ₹349/semester — use those).
- "Payments integrated" when it is sandbox — an instant credibility loss, and the
  brief forbids real transactions.
- Cloud cost copied from a blog instead of computed from drivers.
- Claims of users/traction from the 12 seeded demo profiles.
- Marketing plan with no channel, no budget, no metric.
- Legal section that says "we comply" without naming DPDP/consent/retention.
- A deck that ignores the official 16-slide template, or adds appendix slides to the
  submitted file instead of using the backup deck.
- Fabricated references, or references you never opened.
- Screenshots of a different product (or of the old Node version with a different UI).

---

## 13. Handoffs and boundaries

**Yours:** `01`–`15` above, research notes, cost spreadsheet, deck, package index.

**Engineering team's:** code, UML, README, testing package (plan, cases, JUnit,
coverage, defect log, security/usability evidence), cloud architecture/deployment/
monitoring documents, live URL.

**Shared:** final report (you write business/e-commerce/market/funding chapters; they
write Java/testing/cloud chapters), 5-minute demo video script (you write the
storyline and voice-over; they record the product flow), viva prep (everyone must be
able to explain every part — read the other subjects' summaries).

**Ask the team before finalising:**
1. Entity/incorporation status (drives DPIIT readiness and the compliance note).
2. Whether the pitch presents the team as seeking funding or as bootstrapped.
3. The final price points (₹99 / ₹349 are in the code today — changing them means a
   code change, so confirm early).
4. Which campus is the launch campus for the marketing plan.
5. The confirmed cloud provider/region and the frozen cost figures after deployment.
6. Consent + anonymity rules for interview quotes (and who stores the notes).

---

## 14. Definition of done for the business package

- [ ] All files in §4 exist, named exactly, with version + owner in `00-package-index.md`
- [ ] §9 traceability table has no empty cell
- [ ] Prices, plan names, feature names and numbers identical everywhere
- [ ] Every statistic has a source line or an `[ASSUMPTION]` label
- [ ] No credentials, no real personal data, no unlicensed assets
- [ ] Payments consistently described as **sandbox/test mode only**
- [ ] Costs computed from drivers; spreadsheet committed next to the doc
- [ ] Interviews/usability evidence collected, anonymised, cited
- [ ] Deck follows the official format (cover + Slides 1–16), exports to PDF, and every slide has speaker notes
- [ ] `17-presentation-viva-pack.md` has the timed script, video storyboard, Q&A bank and numbers sheet
- [ ] `18-roles-contribution.md` shows every member's technical contribution and review attendance
- [ ] One teammate peer-reviewed each document; feedback log recorded
- [ ] "Open questions / next steps" section present in every document
- [ ] Honest limitations included (auth, cold starts, moderation, free-tier caps)

---

### Appendix A — reusable one-liner about Twofold (for bios, posters, slides)

> Twofold is a peer-to-peer skill exchange for college students. You list what you
> can teach and what you want to learn; Twofold ranks the people who can teach you by
> how much you both gain, and you book a session on campus or online. Every exchange
> ends in a review, so trust compounds. Teach what you know. Learn what you don't.

### Appendix B — sources to keep in `docs/business/Sources.md`

Record: title, publisher, URL, access date, and the one sentence you took from it.
Minimum set to gather: AISHE enrolment figures; one credible stat on student skill
gap or online-learning spend in India; competitor pricing pages; DPDP Act 2023
(MeitY); Startup India / DPIIT eligibility page; GST threshold for services; Render,
Neon and GitHub Actions pricing (already researched in
`docs/cloud/FREE_TIER_RESEARCH.md`, dated 22 Nov 2026); the open-source licences of
React, Vite, Spring Boot and the bundled fonts.

### Appendix C — what NOT to write

Do not claim: a live URL that does not exist; real payments; user numbers beyond your
own research; a registered trademark; DPIIT recognition already granted; partnerships
with colleges; a patent; a valuation; "AI-powered" anything; "zero carbon" anything.
All of these are checkable, and a panel will check.
