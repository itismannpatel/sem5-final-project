import type { ReactNode } from 'react'
import { Link, NavLink, useLocation } from 'react-router-dom'
import { useApp } from '../context/AppContext'
import { unreadTotal } from '../data/api'
import { Avatar } from './Avatar'
import { Icon, type IconName } from './icons'

interface NavEntry {
  to: string
  label: string
  short: string
  icon: IconName
  badge?: number
}

/** Route → the title the sticky topbar (and the page's h1) shows. */
function pageTitle(pathname: string, partnerName?: string): string {
  if (pathname.startsWith('/messages')) return partnerName ? `Messages · ${partnerName}` : 'Messages'
  if (pathname.startsWith('/u/')) return 'Student profile'
  if (pathname.startsWith('/profile')) return 'My profile'
  if (pathname.startsWith('/dashboard')) return 'Dashboard'
  if (pathname.startsWith('/discover')) return 'Discover'
  if (pathname.startsWith('/matches')) return 'Matches'
  if (pathname.startsWith('/sessions')) return 'Sessions'
  if (pathname.startsWith('/pro')) return 'Twofold Pro'
  return 'Twofold'
}

export function AppShell({ children }: { children: ReactNode }) {
  const { db, user, signOut } = useApp()
  const location = useLocation()

  if (!user) return null

  const unread = unreadTotal(user.id, db)
  const pending = db.sessions.filter(
    (session) => session.status === 'proposed' && session.teacherId === user.id,
  ).length

  const partnerId = location.pathname.startsWith('/messages/')
    ? location.pathname.slice('/messages/'.length)
    : null
  const partner = partnerId ? db.users.find((candidate) => candidate.id === partnerId) : undefined

  const entries: NavEntry[] = [
    { to: '/discover', label: 'Discover', short: 'Discover', icon: 'compass' },
    { to: '/matches', label: 'Matches', short: 'Matches', icon: 'sparkles' },
    { to: '/sessions', label: 'Sessions', short: 'Sessions', icon: 'calendar', badge: pending },
    { to: '/messages', label: 'Messages', short: 'Chat', icon: 'message', badge: unread },
  ]

  const isChat = location.pathname.startsWith('/messages')

  return (
    <div className="shell">
      <aside className="sidebar">
        <Link to="/dashboard" className="brand" aria-label="Twofold home">
          <span className="brand-mark">
            <Icon name="swap" size={17} strokeWidth={2} />
          </span>
          <span className="brand-name">
            twofold<span>.</span>
          </span>
        </Link>

        <nav className="nav" aria-label="Main">
          <div className="nav-label">Overview</div>
          <NavLink
            to="/dashboard"
            className={({ isActive }) => `nav-item${isActive ? ' is-active' : ''}`}
          >
            <Icon name="home" size={18} />
            <span>Dashboard</span>
          </NavLink>

          <div className="nav-label" style={{ marginTop: 18 }}>
            Explore
          </div>
          {entries.map((entry) => (
            <NavLink
              key={entry.to}
              to={entry.to}
              className={({ isActive }) => `nav-item${isActive ? ' is-active' : ''}`}
            >
              <Icon name={entry.icon} size={18} />
              <span>{entry.label}</span>
              {entry.badge ? <span className="nav-count">{entry.badge}</span> : null}
            </NavLink>
          ))}

          <div className="nav-label" style={{ marginTop: 18 }}>
            Account
          </div>
          <NavLink
            to="/profile"
            className={({ isActive }) => `nav-item${isActive ? ' is-active' : ''}`}
          >
            <Icon name="user" size={18} />
            <span>My profile</span>
          </NavLink>
          <NavLink
            to="/pro"
            className={({ isActive }) => `nav-item nav-item-pro${isActive ? ' is-active' : ''}`}
          >
            <Icon name="crown" size={18} />
            <span>Twofold Pro</span>
          </NavLink>
        </nav>

        <div className="sidebar-foot">
          <div className="user-card">
            <Link
              to="/profile"
              style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0, flex: 1 }}
            >
              <Avatar user={user} size="sm" />
              <span className="user-card-meta">
                <span className="user-card-name">{user.name}</span>
                <span className="user-card-sub">{user.branch}</span>
              </span>
            </Link>
            <Link
              to="/profile"
              className="icon-btn icon-btn-sm"
              aria-label="Settings and profile"
              title="Settings and profile"
            >
              <Icon name="settings" size={15} />
            </Link>
            <button
              type="button"
              className="icon-btn icon-btn-sm"
              onClick={signOut}
              aria-label="Sign out"
              title="Sign out"
            >
              <Icon name="logout" size={15} />
            </button>
          </div>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <h1 className="topbar-title">{pageTitle(location.pathname, partner?.name)}</h1>
          <div className="topbar-actions">
            <Link to="/pro" className="btn btn-quiet btn-sm">
              <Icon name="crown" size={15} />
              Twofold Pro
            </Link>
            <Link to="/profile" className="topbar-avatar" aria-label="My profile">
              <Avatar user={user} size="sm" />
            </Link>
          </div>
        </header>

        <header className="mobile-top">
          <Link to="/dashboard" className="brand" style={{ padding: 0 }}>
            <span className="brand-mark">
              <Icon name="swap" size={16} strokeWidth={2} />
            </span>
            <span className="brand-name" style={{ fontSize: '1.25rem' }}>
              twofold<span>.</span>
            </span>
          </Link>
          <div className="row gap-8">
            <Link to="/profile" aria-label="My profile">
              <Avatar user={user} size="sm" />
            </Link>
          </div>
        </header>

        <main className={isChat ? 'content content-flush' : 'content'}>{children}</main>
      </div>

      <nav className="mobile-tabs" aria-label="Main">
        {[
          { to: '/dashboard', label: 'Dashboard', short: 'Home', icon: 'home' as IconName },
          ...entries,
          { to: '/profile', label: 'Profile', short: 'Profile', icon: 'user' as IconName },
        ].map((entry) => (
            <NavLink
              key={entry.to}
              to={entry.to}
              className={({ isActive }) => `mobile-tab${isActive ? ' is-active' : ''}`}
            >
              <Icon name={entry.icon} size={19} />
              <span>{entry.short}</span>
            </NavLink>
          ),
        )}
      </nav>
    </div>
  )
}
