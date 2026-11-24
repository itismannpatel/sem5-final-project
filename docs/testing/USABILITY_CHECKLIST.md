# Twofold — Usability Test Checklist & Script

> **Status: instrument only — no session has been run yet.** The results tables below are
> deliberately empty. Fill them from a real session with real people; do not estimate or
> invent numbers. The brief asks for a usability check of the product, and a small honest
> session beats a fabricated one, which a viva question would expose in seconds.
>
> **Who runs it:** any team member. **Time:** ~15 minutes per participant plus 5 minutes
> setup. **Participants:** 5 is enough to expose the big problems; 8 is the ceiling before
> the patterns stop changing.

---

## 1. What we are trying to learn

Not "do they like it" — but:

1. Can a new student understand **what Twofold is** and what a "two-way swap" means
   without being told?
2. Can they complete the core loop — **find a match → request a session → accept → review** —
   without help?
3. Where do the **match score and the barter model** confuse them?
4. Does the **Pro panel** read as an optional upgrade rather than a paywall on a free product?

---

## 2. Setup (before the participant arrives)

- [ ] Backend + frontend running: `npm run dev` → `http://localhost:5173`
- [ ] Fresh data: `curl -X POST localhost:8080/api/reset` while the dev stack is running (the UI has no reset control)
- [ ] Browser at 100 % zoom, in a **new profile/incognito window** (no saved identity)
- [ ] Recorder ready: screen + audio, or a second person taking notes
- [ ] Printed/duplicated: consent line (§4), task sheet (§5), observation sheet (§6)
- [ ] Agree who speaks: one **facilitator** (asks tasks, never helps) and, if possible,
      one **note-taker** (observes, silent)

---

## 3. Participant profile (record, don't filter)

| Field | Notes |
|---|---|
| Code (P1, P2 …) | No real names in notes |
| Year / branch | And whether they have used a peer-learning app before |
| Comfort with web apps (1–5, self-rated) | 1 = rarely, 5 = daily |
| Did they know Twofold before? | Yes/No — record, but do not exclude |

Aim for a mix: at least two people who have never seen the app, and at least one who is
not from the course (a friend, a sibling, a classmate from another branch) so the jargon
test is real.

---

## 4. Consent and privacy (read aloud, 30 seconds)

> "This is a usability test of a student project, not a test of you. I'll ask you to do a
> few things in the app and think out loud; there are no wrong answers. I'll take notes and
> maybe record the screen — the recording stays with the team, is not published, and you can
> ask me to delete it afterwards. You can stop at any time. May I start?"

- [ ] Consent given (verbal is fine; note it)
- [ ] Recording started (only if consented)
- [ ] Participant told: **the data is seeded demo data, not real people**

---

## 5. Tasks (read the task, never the solution)

Give one task at a time. If the participant asks "is this right?", reply **"what do you
think?"** — help only after 2 minutes of being stuck, and mark the task as *assisted*.

| # | Task (read verbatim) | Success looks like | Time limit |
|---|---|---|---|
| T1 | "Open the app and tell me what it does, in your own words." | Explains a two-way skill swap without using the word "marketplace" only | 60 s |
| T2 | "Sign in as any student." | Lands in the app with a profile | 30 s |
| T3 | "Find someone who can teach you something you'd like to learn." | Reaches Discover or Matches and identifies a person + skill | 90 s |
| T4 | "Ask them for a session at a time that suits you." | Request sent with at least one slot | 120 s |
| T5 | "Now switch to the other side: accept a request someone sent *you*." | Accepts a proposed session; a confirmed time appears | 90 s |
| T6 | "The session is over. Leave them a review." | Review posted, appears on the profile | 90 s |
| T7 | "Find out whether Twofold Pro is active for you, and what it costs." | Reads status + ₹99/₹349 plans on the Twofold Pro page (or their own profile) | 60 s |
| T8 | "Send a message in the conversation you already have." | Message appears in thread | 60 s |

**Deliberately not asked:** how to reset, how to edit skills, the dashboard — those are
secondary and would eat the session.

---

## 6. Observation sheet (one per participant)

For each task: **S** = success without help · **A** = assisted · **F** = failed ·
**T** = time in seconds. Note *where* they hesitated, what they said, what they clicked
first — quotes are worth more than ratings.

| Task | S/A/F | T (s) | Observed friction / quote |
|---|---|---|---|
| T1 | | | |
| T2 | | | |
| T3 | | | |
| T4 | | | |
| T5 | | | |
| T6 | | | |
| T7 | | | |
| T8 | | | |

**Closing questions (ask after all tasks):**

1. "What was the most confusing moment?" → _verbatim_
2. "What did you expect to happen that didn't?"
3. "Who do you think Twofold Pro is for?" (checks whether the paywall reads as optional)
4. "Would you use this? Why / why not?"
5. **SUS** — the 10 standard statements, 1 (strongly disagree) to 5 (strongly agree).

### SUS (System Usability Scale — standard wording, do not paraphrase)

| # | Statement | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|
| 1 | I think that I would like to use this system frequently. | | | | | |
| 2 | I found the system unnecessarily complex. | | | | | |
| 3 | I thought the system was easy to use. | | | | | |
| 4 | I think that I would need the support of a technical person to be able to use this system. | | | | | |
| 5 | I found the various functions in this system were well integrated. | | | | | |
| 6 | I thought there was too much inconsistency in this system. | | | | | |
| 7 | I would imagine that most people would learn to use this system very quickly. | | | | | |
| 8 | I found the system very cumbersome to use. | | | | | |
| 9 | I felt very confident using the system. | | | | | |
| 10 | I needed to learn a lot of things before I could get going with this system. | | | | | |

**Scoring:** for odd items subtract 1; for even items subtract the score from 5; sum and
multiply by 2.5 → 0–100. Report the **mean across participants**, and remember a single
SUS score is meaningless: < 68 is "below average", 68–80 is "good", > 80 is "excellent".
For 5 participants, report the range too — it is usually wide.

---

## 7. Results (fill after the session — do not pre-fill)

**Session:** _date_ · _n_ participants · facilitator _name_ · note-taker _name_

| Metric | Value |
|---|---|
| Task success rate (S only) | _x / 8 tasks × n_ |
| Assisted / failed tasks | _list_ |
| Median time, T3–T6 (core loop) | _s_ |
| SUS mean (range) | _–_ |
| Participants who misread Pro as required | _x / n_ |

**Top 3 problems, ranked by how many participants hit them:**

1. _(problem — evidence: how many, which task, quote)_
2. …
3. …

**What we will fix now vs. log:** _(fix list with owner; anything not fixed goes to
`DEFECT_LOG.md` if it is a defect, or to a "known limitations" line if it is a design
choice)_

---

## 8. Ethics and handling

- Notes use participant codes, not names; the key linking codes to people stays with the
  facilitator and is deleted after the report is written.
- Recordings are not committed to the repository (they are personal data under DPDP; the
  project's own privacy section says so).
- Nothing is "retested until it looks good": if a task fails, that is the finding.
- Report the sample honestly — "5 students, 2 outside the course, all comfortable with web
  apps" is a limitation, not a footnote to hide.

---

## 9. Definition of done for this document

- [ ] ≥ 5 participants run, each with a completed observation sheet
- [ ] SUS scored per participant, mean and range reported
- [ ] Top 3 problems written with counts and quotes
- [ ] Fix list agreed, with owners
- [ ] Results summarised in the final report's testing chapter (2–3 paragraphs + one table)
- [ ] This file's §7 filled in — **not** left as a template
