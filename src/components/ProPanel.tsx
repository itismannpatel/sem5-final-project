import { useEffect, useState } from 'react'
import { fetchPremiumStatus, purchasePremium } from '../data/api'
import type { PremiumStatus, User } from '../data/types'
import { useApp } from '../context/AppContext'
import { useToast } from './Toast'
import { Icon } from './icons'

const PLANS = [
  { code: 'pro_monthly', label: 'Monthly', price: '₹99', note: '30 days' },
  { code: 'pro_semester', label: 'Semester', price: '₹349', note: '180 days' },
] as const

/**
 * Twofold Pro checkout (D4) — sandbox only, but the whole flow is real:
 * plan selection → token capture → POST /api/premium → 201 with the ledger,
 * or 402 with the gateway's message. The card token is never stored by the
 * browser beyond this form, and the server keeps only a masked copy.
 */
export function ProPanel({ user }: { user: User }) {
  const { db } = useApp()
  const toast = useToast()
  const [status, setStatus] = useState<PremiumStatus | null>(null)
  const [plan, setPlan] = useState<string>('pro_monthly')
  const [token, setToken] = useState('tok_visa_4242')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  // Refetch when the ledger changes, so the panel reflects a purchase made here
  // or in another tab without an extra endpoint.
  useEffect(() => {
    let alive = true
    void fetchPremiumStatus(user.id).then((next) => {
      if (alive) setStatus(next)
    })
    return () => {
      alive = false
    }
  }, [user.id, db.payments.length])

  const buy = async () => {
    if (busy) return
    setBusy(true)
    setError('')
    const result = await purchasePremium(user.id, plan, token.trim())
    setBusy(false)
    if (result.ok) {
      toast('Twofold Pro activated — sandbox payment captured')
      return
    }
    setError(result.error)
    toast('Payment declined — nothing was charged', 'error')
  }

  return (
    <div className="card">
      <div className="row between gap-10" style={{ marginBottom: 4 }}>
        <div className="eyebrow">Twofold Pro</div>
        <span className={status?.active ? 'chip chip-mint' : 'chip'}>
          {status?.active ? 'Active' : 'Free plan'}
        </span>
      </div>

      <p className="faint" style={{ fontSize: '0.8rem', margin: '6px 0 14px' }}>
        {status?.active
          ? `Pro ${status.plan?.replace('pro_', '') ?? ''} active${
              status.until ? ` until ${new Date(status.until).toLocaleDateString('en-IN')}` : ''
            }.`
          : 'Keep the barter free forever; Pro adds priority matching and unlimited session requests.'}
      </p>

      <div className="stack gap-8" role="radiogroup" aria-label="Choose a plan">
        {PLANS.map((option) => {
          const selected = plan === option.code
          return (
            <button
              key={option.code}
              type="button"
              role="radio"
              aria-checked={selected}
              className={selected ? 'btn btn-soft btn-block' : 'btn btn-ghost btn-block'}
              onClick={() => setPlan(option.code)}
              style={{ justifyContent: 'space-between' }}
            >
              <span>
                {option.label} <span className="faint">· {option.note}</span>
              </span>
              <strong>{option.price}</strong>
            </button>
          )
        })}
      </div>

      <div className="field" style={{ marginTop: 12 }}>
        <label htmlFor="pro-token">Card token (sandbox)</label>
        <input
          id="pro-token"
          className="input"
          value={token}
          onChange={(event) => setToken(event.target.value)}
          autoComplete="off"
          spellCheck={false}
        />
        <span className="field-hint">
          Test mode only — no real money. Use <code>tok_decline_1</code> to see a decline.
        </span>
      </div>

      {error && (
        <p className="faint" role="alert" style={{ color: 'var(--ember)', fontSize: '0.78rem' }}>
          {error}
        </p>
      )}

      <button
        type="button"
        className="btn btn-primary btn-block"
        style={{ marginTop: 10 }}
        onClick={() => void buy()}
        disabled={busy || !token.trim()}
      >
        <Icon name="sparkles" size={15} />
        {busy ? 'Processing…' : `Pay ${PLANS.find((p) => p.code === plan)?.price ?? ''}`}
      </button>

      <p className="faint" style={{ fontSize: '0.72rem', marginTop: 8 }}>
        {status?.payments ? `${status.payments} sandbox payment(s) on record.` : 'No payments on record.'}
      </p>
    </div>
  )
}
