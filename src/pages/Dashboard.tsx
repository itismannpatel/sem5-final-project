import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useApp, useCurrentUser } from '../context/AppContext'
import { listSessionsForUser, listThreads, ratingFor, unreadTotal } from '../data/api'
import { computeMatches } from '../lib/matching'
import { useServerMatches } from '../data/useMatches'
import { SessionCard } from '../components/SessionCard'
import { MatchRing } from '../components/MatchRing'
import { Avatar } from '../components/Avatar'
import { RatingStars } from '../components/Rating'
import { SkillChip } from '../components/SkillChip'
import { EmptyState } from '../components/EmptyState'
import { Icon } from '../components/icons'

export function Dashboard() {
  const { db } = useApp()
  const me = useCurrentUser()

  const sessions = listSessionsForUser(me.id, db)
  const upcoming = sessions
    .filter((session) => session.status === 'confirmed')
    .sort((a, b) => (a.scheduledFor ?? '').localeCompare(b.scheduledFor ?? ''))
  const requests = sessions.filter(
    (session) => session.status === 'proposed' && session.teacherId === me.id,
  )
  const completed = sessions.filter((session) => session.status === 'completed')
  const unread = unreadTotal(me.id, db)
  const threads = listThreads(me.id, db)
  const rating = ratingFor(me.id, db)

  // The backend engine is authoritative; the local engine paints the first frame.
  const localMatches = useMemo(() => computeMatches(me, db), [me, db])
  const matches = useServerMatches(me.id, 'one', db.version) ?? localMatches
  const twoWay = matches.filter((match) => match.reciprocal)
  const top = matches.slice(0, 3)

  const nextSession = upcoming[0]
  const firstName = me.name.split(' ')[0]

  const stats = [
    { label: 'Two-way swaps', value: twoWay.length, to: '/matches', icon: 'sparkles' as const },
    { label: 'Awaiting you', value: requests.length, to: '/sessions', icon: 'clock' as const },
    { label: 'Upcoming', value: upcoming.length, to: '/sessions', icon: 'calendar' as const },
    { label: 'Unread', value: unread, to: '/messages', icon: 'message' as const },
  ]

  return (
    <>
      <header className="page-head">
        <p>
          Welcome back, {firstName}.{' '}
          {requests.length > 0
            ? `${requests.length} session ${requests.length === 1 ? 'request is' : 'requests are'} waiting for your reply.`
            : twoWay.length > 0
              ? `You have ${twoWay.length} two-way ${twoWay.length === 1 ? 'swap' : 'swaps'} ready to start.`
              : 'Everything is quiet — add skills to your profile to unlock matches.'}
        </p>
        <div className="row gap-8">
          <Link to="/discover" className="btn btn-ghost btn-sm">
            <Icon name="compass" size={15} />
            Discover
          </Link>
          <Link to="/matches" className="btn btn-primary btn-sm">
            <Icon name="sparkles" size={15} />
            Find a swap
          </Link>
        </div>
      </header>

      <div className="dash-stats">
        {stats.map((stat) => (
          <Link key={stat.label} to={stat.to} className="card card-hover dash-stat">
            <span className="feature-icon" style={{ margin: 0 }}>
              <Icon name={stat.icon} size={16} />
            </span>
            <span className="stat">
              <span className="stat-value">{stat.value}</span>
              <span className="stat-label">{stat.label}</span>
            </span>
          </Link>
        ))}
      </div>

      <div className="dash-grid">
        <section>
          <div className="section-head">
            <h2 className="section-title">Next up</h2>
            <Link to="/sessions" className="btn btn-quiet btn-sm">
              All sessions
              <Icon name="arrowRight" size={14} />
            </Link>
          </div>
          {nextSession ? (
            <div className="session-list">
              <SessionCard session={nextSession} />
            </div>
          ) : (
            <EmptyState
              icon="calendar"
              title="Nothing booked yet"
              text={
                requests.length > 0
                  ? 'Confirm one of the proposed times and your next swap lands here.'
                  : 'Accept a request or send one from a match — confirmed sessions appear here.'
              }
              action={
                <Link to={requests.length > 0 ? '/sessions' : '/matches'} className="btn btn-primary btn-sm">
                  {requests.length > 0 ? 'Review requests' : 'Browse matches'}
                </Link>
              }
            />
          )}
        </section>

        <section>
          <div className="section-head">
            <h2 className="section-title">Strongest matches</h2>
            <Link to="/matches" className="btn btn-quiet btn-sm">
              See all
              <Icon name="arrowRight" size={14} />
            </Link>
          </div>

          {top.length === 0 ? (
            <EmptyState
              icon="target"
              title="No matches yet"
              text="Tell Twofold what you want to learn — every student who can teach it shows up here."
              action={
                <Link to="/profile" className="btn btn-primary btn-sm">
                  Add skills to my profile
                </Link>
              }
            />
          ) : (
            <div className="stack gap-10">
              {top.map((match) => (
                <Link key={match.user.id} to={`/u/${match.user.id}`} className="card card-hover dash-match">
                  <Avatar user={match.user} size="md" />
                  <span className="grow" style={{ minWidth: 0 }}>
                    <span className="person-name" style={{ display: 'flex' }}>
                      {match.user.name}
                      {match.user.verified && (
                        <span className="verified-dot" title="Verified student">
                          <Icon name="checkCircle" size={13} strokeWidth={2} />
                        </span>
                      )}
                    </span>
                    <span className="person-meta">{match.user.branch} · {match.user.year}</span>
                    <span className="chip-row" style={{ marginTop: 6 }}>
                      {match.canLearn.slice(0, 1).map((skill) => (
                        <SkillChip key={skill.skillId} skillId={skill.skillId} tone="learn" />
                      ))}
                      {match.canTeach.slice(0, 1).map((skill) => (
                        <SkillChip key={skill.skillId} skillId={skill.skillId} tone="teach" />
                      ))}
                    </span>
                  </span>
                  <MatchRing score={match.score} size={48} stroke={3.5} />
                </Link>
              ))}
            </div>
          )}
        </section>
      </div>

      <div className="dash-grid" style={{ marginTop: 26 }}>
        <section>
          <div className="section-head">
            <h2 className="section-title">Recent messages</h2>
            <Link to="/messages" className="btn btn-quiet btn-sm">
              Open inbox
              <Icon name="arrowRight" size={14} />
            </Link>
          </div>

          {threads.length === 0 ? (
            <EmptyState
              icon="message"
              title="No conversations yet"
              text="Message a match to agree a time — your threads collect here."
            />
          ) : (
            <div className="card">
              <div className="stack">
                {threads.slice(0, 3).map((thread) => {
                  const partner = db.users.find((candidate) => candidate.id === thread.partnerId)
                  if (!partner) return null
                  return (
                    <Link
                      key={thread.partnerId}
                      to={`/messages/${thread.partnerId}`}
                      className="row gap-10 dash-thread"
                    >
                      <Avatar user={partner} size="sm" />
                      <span className="grow" style={{ minWidth: 0 }}>
                        <span className="row between gap-8">
                          <b style={{ fontSize: '0.83rem' }}>{partner.name}</b>
                          <span className="faint" style={{ fontSize: '0.68rem', flex: 'none' }}>
                            {new Date(thread.lastMessage.createdAt).toLocaleDateString(undefined, {
                              month: 'short',
                              day: 'numeric',
                            })}
                          </span>
                        </span>
                        <span className="faint" style={{ fontSize: '0.74rem', display: 'block', marginTop: 2, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {thread.lastMessage.fromUserId === me.id ? 'You: ' : ''}
                          {thread.lastMessage.body}
                        </span>
                      </span>
                      {thread.unreadCount > 0 && <span className="badge-count">{thread.unreadCount}</span>}
                    </Link>
                  )
                })}
              </div>
            </div>
          )}
        </section>

        <section>
          <div className="section-head">
            <h2 className="section-title">Your reputation</h2>
            <Link to="/profile" className="btn btn-quiet btn-sm">
              My profile
              <Icon name="arrowRight" size={14} />
            </Link>
          </div>

          <div className="card stack gap-14">
            <div className="row between gap-12 wrap">
              <div className="row gap-10">
                <Avatar user={me} size="lg" />
                <div>
                  <div style={{ fontWeight: 600, fontSize: '0.92rem' }}>{me.name}</div>
                  <div className="person-meta">{me.branch} · {me.year}</div>
                </div>
              </div>
              <RatingStars value={rating.average} count={rating.count} size={13} />
            </div>

            <hr className="divider" />

            <div className="row between gap-12 wrap">
              <span className="stat">
                <span className="stat-value">{completed.length}</span>
                <span className="stat-label">Exchanges done</span>
              </span>
              <span className="stat">
                <span className="stat-value">{me.teaches.length}</span>
                <span className="stat-label">Skills taught</span>
              </span>
              <span className="stat">
                <span className="stat-value">{me.wants.length}</span>
                <span className="stat-label">Skills learning</span>
              </span>
            </div>

            <Link to="/profile" className="btn btn-outline btn-block">
              Edit my profile
            </Link>
          </div>
        </section>
      </div>
    </>
  )
}
