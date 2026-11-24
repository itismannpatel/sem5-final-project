import { Link } from 'react-router-dom'
import { useApp } from '../context/AppContext'
import { SKILL_BY_ID } from '../data/catalog'
import { usePublicStats } from '../data/stats'
import { MATCH_WEIGHTS } from '../lib/matching'
import { Avatar } from '../components/Avatar'
import { MatchRing } from '../components/MatchRing'
import { Reveal } from '../components/Reveal'
import { Icon, type IconName } from '../components/icons'
import type { User } from '../data/types'

const STEPS = [
  {
    title: 'List your two sides',
    text: 'Add the skills you can actually teach and the ones you want to learn. Levels included — “beginner Rust” is not the same as “advanced”.',
  },
  {
    title: 'Meet your match',
    text: 'The engine ranks students by how much of a two-way swap they represent, and shows exactly why each score landed where it did.',
  },
  {
    title: 'Swap, then rate',
    text: 'Agree a time, meet online or on campus, and leave a short review. The next student searching that skill sees your words.',
  },
]

const VALUE_BAD = [
  { title: 'Money leaves campus', text: 'A ₹2,000 course is a one-way transfer — nobody in your class gets anything back.' },
  { title: 'Rigid, one-size schedules', text: 'Recorded lectures and batch timings bend for nobody’s timetable.' },
  { title: 'You watch, you don’t teach', text: 'Half of learning is explaining — paid courses never make you the teacher.' },
  { title: 'Forgotten in a month', text: 'No accountability, no reviews, no one waiting to see you show up.' },
]

const VALUE_GOOD = [
  { title: 'Zero rupees change hands', text: 'One hour of what you know buys one hour of what you don’t. The currency is effort.' },
  { title: 'Two-way by design', text: 'Every swap has you teaching and learning — reciprocity is the whole point.' },
  { title: 'Your reputation compounds', text: 'Each completed exchange ends in a public review that feeds your match score.' },
  { title: 'A campus network, not a cart', text: 'You meet the person you’ll ask next semester — not a playlist.' },
]

const WEIGHT_ROWS: Array<{ icon: IconName; key: keyof typeof MATCH_WEIGHTS; name: string; desc: string }> = [
  { icon: 'book', key: 'learn', name: 'Learn', desc: 'What they can teach you — the skill you came looking for.' },
  { icon: 'handshake', key: 'teach', name: 'Reciprocity', desc: 'What you can teach them — the swap has to run both ways.' },
  { icon: 'target', key: 'affinity', name: 'Affinity', desc: 'Shared subject areas you both list or want.' },
  { icon: 'shield', key: 'trust', name: 'Trust', desc: 'Review history from completed exchanges.' },
]

const TRUST_CARDS = [
  {
    icon: 'award' as IconName,
    title: 'Reviews you can measure',
    text: 'Every completed exchange ends with a rating and a short written review. They are public on each profile and feed back into matching, so good teachers surface first.',
  },
  {
    icon: 'badgeCheck' as IconName,
    title: 'Real campus identities',
    text: 'Profiles carry a name, branch and year — no anonymous accounts. Verified students are badged so you know who you are meeting before you message them.',
  },
  {
    icon: 'zap' as IconName,
    title: 'Nothing to install',
    text: 'Twofold runs in the browser against a Spring Boot API. Chat, scheduling and calendar export work as soon as you sign in — no card, no subscription needed.',
  },
]

function firstSkill(list: User['teaches']): string | null {
  const entry = list[0]
  if (!entry) return null
  return SKILL_BY_ID[entry.skillId]?.name ?? null
}

export function Landing() {
  const { user } = useApp()
  // The landing is public: everything it shows comes from /api/stats, which
  // carries aggregates and sanitized cards — never user rows.
  const stats = usePublicStats()
  const course = user ? '/dashboard' : '/auth'

  const featured = stats?.featured ?? []
  const aarav = featured.find((person) => person.id === 'u_aarav')
  const riya = featured.find((person) => person.id === 'u_riya')
  const heroScore = stats?.heroScore ?? 0

  const completed = stats?.completed ?? 0
  const average = stats ? stats.rating.average.toFixed(1) : '—'
  const quote = stats?.quote ?? null

  return (
    <div className="landing">
      <nav className="landing-nav">
        <Link to="/" className="brand">
          <span className="brand-mark">
            <Icon name="swap" size={17} strokeWidth={2} />
          </span>
          <span className="brand-name">
            twofold<span>.</span>
          </span>
        </Link>
        <div className="landing-links">
          <a href="#how">How it works</a>
          <a href="#matching">Matching</a>
          <a href="#trust">Trust</a>
          <a href="#pro">Pro</a>
          <Link to={course} className="btn btn-primary btn-sm">
            {user ? 'Back to app' : 'Sign in'}
            <Icon name="arrowRight" size={15} />
          </Link>
        </div>
      </nav>

      {/* 1 — Hero */}
      <header className="hero">
        <div>
          <span className="hero-badge">
            <span className="dot" />
            Peer-to-peer · free to join
          </span>

          <h1>
            Learn something new.
            <br />
            Teach <em>something you love.</em>
          </h1>

          <p className="hero-sub">
            Twofold pairs students who can teach a skill with students who want to learn it — and
            the other way round. No money, no middlemen. Just two people and a fair swap.
          </p>

          <div className="hero-actions">
            <Link to={course} className="btn btn-primary btn-lg">
              {user ? 'Open the app' : 'Create profile'}
              <Icon name="arrowRight" size={17} />
            </Link>
            <a href="#how" className="btn btn-outline btn-lg">
              Explore how it works
            </a>
          </div>

          <div className="hero-stats">
            <div className="stat">
              <span className="stat-value">{stats?.users ?? '—'}</span>
              <span className="stat-label">Students</span>
            </div>
            <div className="stat">
              <span className="stat-value">{stats?.skills ?? '—'}</span>
              <span className="stat-label">Skills listed</span>
            </div>
            <div className="stat">
              <span className="stat-value">{completed}</span>
              <span className="stat-label">Swaps made</span>
            </div>
            <div className="stat">
              <span className="stat-value">{average}</span>
              <span className="stat-label">Avg rating</span>
            </div>
          </div>
        </div>

        {aarav && riya && (
        <div className="hero-art">
          <div className="hero-float hero-float-b">
            <Icon name="sparkles" size={14} />
            <span>
              <strong>2-way swap</strong> detected
            </span>
          </div>

          <div className="hero-panel">
            <div className="row between gap-12" style={{ marginBottom: 16 }}>
              <div className="row gap-10">
                <div style={{ display: 'flex' }}>
                  {aarav && <Avatar user={aarav} size="md" />}
                  {riya && (
                    <div style={{ marginLeft: -10 }}>
                      <Avatar user={riya} size="md" />
                    </div>
                  )}
                </div>
                <div>
                  <div style={{ fontSize: '0.86rem', fontWeight: 600, letterSpacing: '-0.01em' }}>
                    Aarav &amp; Riya
                  </div>
                  <div className="faint" style={{ fontSize: '0.72rem' }}>
                    Computer Science · Interaction Design
                  </div>
                </div>
              </div>
              <MatchRing score={heroScore} size={52} stroke={3.5} />
            </div>

            <div className="exchange">
              <div className="exchange-col">
                <span className="exchange-label teach">
                  <i /> Aarav teaches
                </span>
                <span className="exchange-skill">React</span>
                <span className="chip chip-ember" style={{ alignSelf: 'flex-start' }}>
                  Advanced
                </span>
              </div>
              <span className="exchange-arrow">
                <Icon name="swap" size={15} />
              </span>
              <div className="exchange-col">
                <span className="exchange-label learn">
                  <i /> Riya teaches
                </span>
                <span className="exchange-skill">Figma</span>
                <span className="chip chip-mint" style={{ alignSelf: 'flex-start' }}>
                  Advanced
                </span>
              </div>
            </div>

            <div className="row between gap-10" style={{ marginTop: 14 }}>
              <div className="chip-row">
                <span className="chip">
                  <Icon name="calendar" size={12} />
                  Thu, 5:00 PM
                </span>
                <span className="chip">
                  <Icon name="award" size={12} />
                  4.9 rating
                </span>
              </div>
              <Link to={course} className="btn btn-primary btn-sm">
                Request
              </Link>
            </div>
          </div>

          <div className="hero-float hero-float-a">
            <span className="chip chip-mint">
              <Icon name="checkCircle" size={12} />
              Confirmed
            </span>
            <span>Calendar invite sent</span>
          </div>
        </div>
        )}
      </header>

      {/* 2 — How it works */}
      <section className="band" id="how">
        <Reveal>
          <div className="band-head">
            <span className="eyebrow">How it works</span>
            <h2>
              Three steps, <span className="grad-text">one fair trade.</span>
            </h2>
            <p>
              No listings, no bidding, no money changing hands. Twofold is built around the idea
              that every student already owns something worth teaching.
            </p>
          </div>
        </Reveal>

        <div className="steps">
          {STEPS.map((step, index) => (
            <Reveal key={step.title} delay={index * 90}>
              <div className="step" style={{ height: '100%' }}>
                <span className="step-num">0{index + 1}</span>
                <h3>{step.title}</h3>
                <p>{step.text}</p>
              </div>
            </Reveal>
          ))}
        </div>
      </section>

      {/* 3 — Featured students */}
      <div className="band-tint">
        <section className="band" id="featured">
          <Reveal>
            <div className="band-head">
              <span className="eyebrow">Featured students</span>
              <h2>Real profiles, ready to swap.</h2>
              <p>
                A few of the students already trading skills on campus — sign in to open a full
                profile with their skills, sessions and reviews.
              </p>
            </div>
          </Reveal>

          <div className="featured-grid">
            {featured.map((person, index) => {
              const teaches = firstSkill(person.teaches)
              const wants = firstSkill(person.wants)
              const rating = person.rating
              return (
                <Reveal key={person.id} delay={index * 90}>
                  <div className="student-mini" style={{ height: '100%' }}>
                    <div className="student-mini-top">
                      <Avatar user={person} size="md" />
                      <div style={{ minWidth: 0 }}>
                        <div className="student-mini-name">{person.name}</div>
                        <div className="student-mini-meta">
                          {person.branch} · {person.year}
                        </div>
                      </div>
                      <div className="student-mini-score">
                        <b>{rating.count > 0 ? rating.average.toFixed(1) : '—'}</b>
                        <span>rating</span>
                      </div>
                    </div>

                    <div className="student-mini-swap">
                      <div className="row">
                        <i className="teach" />
                        <span className="faint">Teaches</span>
                        <b>{teaches ?? 'Listing soon'}</b>
                      </div>
                      <div className="row">
                        <i className="learn" />
                        <span className="faint">Wants</span>
                        <b>{wants ?? 'Open to anything'}</b>
                      </div>
                    </div>

                    <Link to={course} className="btn btn-ghost btn-sm" style={{ marginTop: 'auto' }}>
                      View profile
                      <Icon name="arrowRight" size={14} />
                    </Link>
                  </div>
                </Reveal>
              )
            })}
          </div>
        </section>
      </div>

      {/* 4 — Why exchange beats paying */}
      <section className="band" id="value">
        <Reveal>
          <div className="band-head">
            <span className="eyebrow">Why skill exchange</span>
            <h2>
              Better than paying — <span className="grad-text">in every column.</span>
            </h2>
            <p>
              Paid courses sell you content. Twofold puts you in a two-way relationship with a
              student who needs exactly what you are good at.
            </p>
          </div>
        </Reveal>

        <div className="compare">
          <Reveal>
            <div className="compare-col" style={{ height: '100%' }}>
              <span className="compare-label">
                <Icon name="creditCard" size={13} />
                Paying someone
              </span>
              <div className="compare-figure">
                ₹2,000+ <small>for one course, one direction</small>
              </div>
              <ul className="compare-list">
                {VALUE_BAD.map((item) => (
                  <li key={item.title}>
                    <Icon name="x" size={15} strokeWidth={2.2} />
                    <span>
                      <b>{item.title}.</b> {item.text}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </Reveal>

          <Reveal delay={90}>
            <div className="compare-col is-good" style={{ height: '100%' }}>
              <span className="compare-label">
                <Icon name="handshake" size={13} />
                Trading skills
              </span>
              <div className="compare-figure">
                One hour <small>of what you know, for one hour of what you don’t</small>
              </div>
              <ul className="compare-list">
                {VALUE_GOOD.map((item) => (
                  <li key={item.title}>
                    <Icon name="check" size={15} strokeWidth={2.2} />
                    <span>
                      <b>{item.title}.</b> {item.text}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          </Reveal>
        </div>
      </section>

      {/* 5 — How matching works */}
      <div className="band-tint">
        <section className="band" id="matching">
          <Reveal>
            <div className="band-head">
              <span className="eyebrow">How matching works</span>
              <h2>A score you can take apart.</h2>
              <p>
                Four weighted signals, added in the open. Every match page shows this breakdown, so
                you always know why someone ranked where they did.
              </p>
            </div>
          </Reveal>

          <Reveal>
            <div className="matching-panel">
              <div className="matching-panel-copy">
                <h3>
                  Learn <span className="grad-text">45</span> · Reciprocity 35 · Affinity 12 ·
                  Trust 8
                </h3>
                <p>
                  The engine favours students who can teach you something you asked for — and who
                  you can teach back. Shared subjects break ties; reviews keep flaky accounts out
                  of the top rows.
                </p>
                <div className="chip-row" style={{ marginTop: 14 }}>
                  <span className="chip chip-ember">
                    <Icon name="sparkles" size={12} />
                    Server-side engine
                  </span>
                  <span className="chip">
                    <Icon name="refresh" size={12} />
                    Recomputed every change
                  </span>
                </div>
              </div>

              <div className="matching-weights">
                {WEIGHT_ROWS.map((row) => (
                  <div className="weight-row" key={row.key}>
                    <span className="weight-name">
                      <Icon name={row.icon} size={15} />
                      {row.name}
                    </span>
                    <span className="weight-desc">{row.desc}</span>
                    <span className="weight-value">{MATCH_WEIGHTS[row.key]}</span>
                    <span className="weight-track">
                      <span
                        className="weight-fill"
                        style={{ width: `${MATCH_WEIGHTS[row.key] * (100 / 45)}%` }}
                      />
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </Reveal>
        </section>
      </div>

      {/* 6 — Trust & community */}
      <section className="band" id="trust">
        <Reveal>
          <div className="band-head">
            <span className="eyebrow">Trust &amp; community</span>
            <h2>Strangers, but vouched for.</h2>
            <p>
              Exchanging with someone you have never met only works when reputation is visible.
              Twofold makes it the loudest thing on every profile.
            </p>
          </div>
        </Reveal>

        <div className="trust-grid">
          {TRUST_CARDS.map((card, index) => (
            <Reveal key={card.title} delay={index * 90}>
              <div className="trust-card" style={{ height: '100%' }}>
                <h3>
                  <Icon name={card.icon} size={17} />
                  {card.title}
                </h3>
                <p>{card.text}</p>
              </div>
            </Reveal>
          ))}
        </div>

        {quote && (
          <Reveal>
            <blockquote className="trust-quote">
              “{quote.text}”
              <footer>
                {quote.fromName} → {quote.toName} · {quote.rating}
                <Icon name="star" size={12} /> on{' '}
                {new Date(quote.createdAt).toLocaleDateString('en-IN')}
              </footer>
            </blockquote>
          </Reveal>
        )}
      </section>

      {/* 7 — Premium preview */}
      <div className="band-tint">
        <section className="band" id="pro">
          <Reveal>
            <div className="band-head">
              <span className="eyebrow">Twofold Pro</span>
              <h2>
                Free forever. <span className="grad-text">Pro if you live here.</span>
              </h2>
              <p>
                The whole exchange stays free. Pro adds priority matching and unlimited session
                requests — paid in the sandbox with test tokens, so no real money ever moves.
              </p>
            </div>
          </Reveal>

          <div className="pro-preview">
            <Reveal>
              <div className="price-card" style={{ height: '100%' }}>
                <div className="price-name">
                  <Icon name="user" size={15} />
                  Free
                </div>
                <div className="price-amount">
                  ₹0 <span>/ forever</span>
                </div>
                <ul className="feature-list">
                  <li>
                    <Icon name="check" size={15} strokeWidth={2.2} />
                    Discovery, matches and full score breakdowns
                  </li>
                  <li>
                    <Icon name="check" size={15} strokeWidth={2.2} />
                    Sessions, chat and calendar export
                  </li>
                  <li>
                    <Icon name="check" size={15} strokeWidth={2.2} />
                    Public profile and reviews
                  </li>
                </ul>
                <Link to={course} className="btn btn-outline btn-block">
                  Start free
                </Link>
              </div>
            </Reveal>

            <Reveal delay={90}>
              <div className="price-card price-card-pro" style={{ height: '100%' }}>
                <span className="price-badge">
                  <Icon name="crown" size={12} />
                  Pro
                </span>
                <div className="price-name">
                  <Icon name="sparkles" size={15} />
                  Twofold Pro
                </div>
                <div className="price-amount">
                  ₹99 <span>/ 30 days · ₹349 / semester</span>
                </div>
                <ul className="feature-list">
                  <li>
                    <Icon name="check" size={15} strokeWidth={2.2} />
                    Priority matching — you surface first
                  </li>
                  <li>
                    <Icon name="check" size={15} strokeWidth={2.2} />
                    Unlimited session requests
                  </li>
                  <li>
                    <Icon name="check" size={15} strokeWidth={2.2} />
                    Pro badge on your profile
                  </li>
                </ul>
                <Link to={course} className="btn btn-primary btn-block">
                  <Icon name="crown" size={15} />
                  See plans inside the app
                </Link>
              </div>
            </Reveal>
          </div>
        </section>
      </div>

      {/* 8 — Final CTA */}
      <section className="cta-band">
        <Reveal>
          <div className="cta-inner">
            <div>
              <h2>
                Your next skill is <span className="grad-text">one swap away.</span>
              </h2>
              <p>
                Create a profile, list what you can teach and what you want to learn, and the
                matching engine does the rest — no fees, no middleman.
              </p>
            </div>
            <Link to={course} className="btn btn-primary btn-lg">
              {user ? 'Open the app' : 'Create profile'}
              <Icon name="arrowUpRight" size={17} />
            </Link>
          </div>
        </Reveal>
      </section>

      {/* 9 — Footer */}
      <footer className="landing-foot">
        <span className="row gap-8">
          <span className="brand-mark" style={{ width: 24, height: 24, borderRadius: 7 }}>
            <Icon name="swap" size={13} strokeWidth={2} />
          </span>
          Twofold — a peer-to-peer skill exchange for students.
        </span>
        <span className="landing-foot-links">
          <a href="#how">How it works</a>
          <a href="#matching">Matching</a>
          <a href="#trust">Trust</a>
          <a href="#pro">Pro</a>
          <Link to={course}>Sign in</Link>
        </span>
        <span className="row gap-6">
          <Icon name="shield" size={13} />
          Peer-to-peer · no fees
        </span>
      </footer>
    </div>
  )
}
