import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useApp } from '../context/AppContext'
import { usePublicStats } from '../data/stats'
import { Icon } from '../components/icons'
import { useToast } from '../components/Toast'

const YEARS = ['1st year', '2nd year', '3rd year', '4th year', 'Postgraduate']

/**
 * Sign in or create a profile. There are no pre-made accounts and no shared
 * password: an account only exists if it was registered here, and the session
 * it opens is a real server-side session.
 */
export function Auth() {
  const { user, login, register } = useApp()
  const stats = usePublicStats()
  const navigate = useNavigate()
  const toast = useToast()

  const [mode, setMode] = useState<'signin' | 'create'>('signin')
  const [signinForm, setSigninForm] = useState({ identifier: '', password: '' })
  const [form, setForm] = useState({
    name: '',
    email: '',
    password: '',
    branch: '',
    year: YEARS[2],
    bio: '',
  })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  /** Run an auth call, surfacing the server's exact message on failure. */
  const attempt = async (call: () => ReturnType<typeof login>): Promise<boolean> => {
    setBusy(true)
    setError('')
    const result = await call()
    setBusy(false)
    if (!result.ok) {
      setError(result.error)
      return false
    }
    return true
  }

  const signIn = async () => {
    if (!signinForm.identifier.trim() || !signinForm.password) {
      setError('Enter your email (or handle) and password.')
      return
    }
    const ok = await attempt(() =>
      login(signinForm.identifier.trim(), signinForm.password),
    )
    if (!ok) return
    toast('Signed in')
    navigate('/dashboard')
  }

  const create = async () => {
    if (!form.name.trim() || !form.email.trim()) {
      setError('Name and email are required.')
      return
    }
    if (!/.+@.+\..+/.test(form.email.trim())) {
      setError('That email does not look right.')
      return
    }
    if (form.password.length < 8) {
      setError('Choose a password of at least 8 characters.')
      return
    }
    const ok = await attempt(() =>
      register({
        name: form.name.trim(),
        email: form.email.trim(),
        password: form.password,
        branch: form.branch,
        year: form.year,
        bio: form.bio,
      }),
    )
    if (!ok) return
    toast('Profile created — add your skills next')
    navigate('/profile')
  }

  const quote = stats?.quote

  return (
    <div className="auth">
      <aside className="auth-side">
        <Link to="/" className="brand" style={{ padding: 0 }}>
          <span className="brand-mark">
            <Icon name="swap" size={17} strokeWidth={2} />
          </span>
          <span className="brand-name">
            twofold<span>.</span>
          </span>
        </Link>

        <div>
          <p className="auth-quote">
            “{quote?.text ?? 'One hour of what you know buys one hour of what you don’t.'}”
          </p>
          {quote && (
            <p className="faint" style={{ marginTop: 12, fontSize: '0.74rem' }}>
              {quote.fromName} → {quote.toName} · {quote.rating}
              <Icon name="star" size={11} />
            </p>
          )}
        </div>

        <div className="auth-side-foot">
          <div className="stat">
            <span className="stat-value">{stats?.users ?? '—'}</span>
            <span className="stat-label">Students</span>
          </div>
          <div className="stat">
            <span className="stat-value">{stats?.skills ?? '—'}</span>
            <span className="stat-label">Skills</span>
          </div>
          <div className="stat">
            <span className="stat-value">{stats?.sessions ?? '—'}</span>
            <span className="stat-label">Sessions</span>
          </div>
        </div>
      </aside>

      <main className="auth-panel">
        <div className="auth-card">
          <h1 className="page-title">
            {mode === 'signin' ? 'Welcome back.' : 'Create your profile.'}
          </h1>
          <p className="muted" style={{ marginTop: 8, marginBottom: 22, fontSize: '0.88rem' }}>
            {mode === 'signin'
              ? 'Sign in with your campus email (or handle) and the password you chose.'
              : 'Your account gets a real password; new profiles start empty and you add skills next.'}
          </p>

          {user && (
            <div
              className="row gap-10"
              style={{
                padding: '10px 12px',
                marginBottom: 16,
                borderRadius: 12,
                border: '1px solid var(--mint-line)',
                background: 'var(--mint-soft)',
                fontSize: '0.8rem',
              }}
            >
              <Icon name="checkCircle" size={15} />
              <span className="grow">
                Signed in as <strong>{user.name}</strong>.
              </span>
              <Link to="/dashboard" className="btn btn-quiet btn-sm">
                Continue
              </Link>
            </div>
          )}

          <div className="auth-switch">
            <button
              type="button"
              className={mode === 'signin' ? 'is-on' : ''}
              onClick={() => {
                setMode('signin')
                setError('')
              }}
            >
              Sign in
            </button>
            <button
              type="button"
              className={mode === 'create' ? 'is-on' : ''}
              onClick={() => {
                setMode('create')
                setError('')
              }}
            >
              Create profile
            </button>
          </div>

          {mode === 'signin' && (
            <div className="stack gap-14">
              <div className="field">
                <label htmlFor="signin-identifier">Email or handle</label>
                <input
                  id="signin-identifier"
                  className="input"
                  placeholder="you@campus.edu"
                  autoComplete="username"
                  value={signinForm.identifier}
                  onChange={(event) =>
                    setSigninForm({ ...signinForm, identifier: event.target.value })
                  }
                />
              </div>

              <div className="field">
                <label htmlFor="signin-password">Password</label>
                <input
                  id="signin-password"
                  className="input"
                  type="password"
                  placeholder="Your password"
                  autoComplete="current-password"
                  value={signinForm.password}
                  onChange={(event) =>
                    setSigninForm({ ...signinForm, password: event.target.value })
                  }
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') void signIn()
                  }}
                />
              </div>

              {error && (
                <div className="row gap-6" style={{ color: 'var(--danger)', fontSize: '0.8rem' }}>
                  <Icon name="x" size={14} />
                  {error}
                </div>
              )}

              <button
                type="button"
                className="btn btn-primary btn-lg btn-block"
                onClick={signIn}
                disabled={busy}
              >
                {busy ? 'Signing in…' : 'Sign in'}
                <Icon name="arrowRight" size={17} />
              </button>
            </div>
          )}

          {mode === 'create' && (
            <div className="stack gap-14">
              <div className="field">
                <label htmlFor="signup-name">Full name</label>
                <input
                  id="signup-name"
                  className="input"
                  placeholder="Priya Nambiar"
                  value={form.name}
                  onChange={(event) => setForm({ ...form, name: event.target.value })}
                />
              </div>

              <div className="field">
                <label htmlFor="signup-email">Campus email</label>
                <input
                  id="signup-email"
                  className="input"
                  type="email"
                  placeholder="priya@campus.edu"
                  autoComplete="email"
                  value={form.email}
                  onChange={(event) => setForm({ ...form, email: event.target.value })}
                />
              </div>

              <div className="field">
                <label htmlFor="signup-password">Password</label>
                <input
                  id="signup-password"
                  className="input"
                  type="password"
                  placeholder="At least 8 characters"
                  autoComplete="new-password"
                  value={form.password}
                  onChange={(event) => setForm({ ...form, password: event.target.value })}
                />
              </div>

              <div className="grid-2" style={{ gap: 12 }}>
                <div className="field">
                  <label htmlFor="signup-branch">Branch</label>
                  <input
                    id="signup-branch"
                    className="input"
                    placeholder="Computer Science"
                    value={form.branch}
                    onChange={(event) => setForm({ ...form, branch: event.target.value })}
                  />
                </div>
                <div className="field">
                  <label htmlFor="signup-year">Year</label>
                  <select
                    id="signup-year"
                    className="select"
                    value={form.year}
                    onChange={(event) => setForm({ ...form, year: event.target.value })}
                  >
                    {YEARS.map((year) => (
                      <option key={year} value={year}>
                        {year}
                      </option>
                    ))}
                  </select>
                </div>
              </div>

              <div className="field">
                <label htmlFor="signup-bio">One line about you</label>
                <textarea
                  id="signup-bio"
                  className="textarea"
                  style={{ minHeight: 74 }}
                  placeholder="What do you build, play or study?"
                  value={form.bio}
                  onChange={(event) => setForm({ ...form, bio: event.target.value })}
                />
              </div>

              {error && (
                <div className="row gap-6" style={{ color: 'var(--danger)', fontSize: '0.8rem' }}>
                  <Icon name="x" size={14} />
                  {error}
                </div>
              )}

              <button
                type="button"
                className="btn btn-primary btn-lg btn-block"
                onClick={create}
                disabled={busy}
              >
                {busy ? 'Creating…' : 'Create profile'}
                <Icon name="arrowRight" size={17} />
              </button>
            </div>
          )}

          <p className="faint" style={{ fontSize: '0.72rem', marginTop: 18, textAlign: 'center' }}>
            <Link to="/" style={{ textDecoration: 'underline', textUnderlineOffset: 3 }}>
              Back to home
            </Link>
          </p>
        </div>
      </main>
    </div>
  )
}
