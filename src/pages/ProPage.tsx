import { Link } from 'react-router-dom'
import { useCurrentUser } from '../context/AppContext'
import { ProPanel } from '../components/ProPanel'
import { Icon } from '../components/icons'

const FREE_FEATURES = [
  'Unlimited student discovery and search',
  'Two-way match scoring with full breakdown',
  'Session requests, chat and calendar export',
  'Public profile, reviews and ratings',
] as const

const PRO_FEATURES = [
  'Priority matching — you surface first',
  'Unlimited session requests',
  'Pro badge on your profile',
  'Everything in Free, forever',
] as const

/**
 * Twofold Pro — the pricing surface. The checkout itself stays in ProPanel
 * (sandbox gateway, D4): this page only presents plans, benefits and status.
 */
export function ProPage() {
  const me = useCurrentUser()

  return (
    <>
      <header className="page-head">
        <p>
          Keep the barter free forever. Pro is for students who live on Twofold — priority matching
          and unlimited requests, paid in the sandbox with test tokens.
        </p>
        <Link to="/discover" className="btn btn-ghost btn-sm">
          <Icon name="compass" size={15} />
          Back to Discover
        </Link>
      </header>

      <div className="pricing-grid">
        <div className="price-card">
          <div className="price-name">
            <Icon name="user" size={15} />
            Free
          </div>
          <div className="price-amount">
            ₹0 <span>/ forever</span>
          </div>
          <p className="price-note">
            The full skill exchange — everything you need to trade one hour for another.
          </p>
          <ul className="feature-list">
            {FREE_FEATURES.map((feature) => (
              <li key={feature}>
                <Icon name="check" size={15} strokeWidth={2.2} />
                {feature}
              </li>
            ))}
          </ul>
          <Link to="/profile" className="btn btn-outline btn-block">
            Current plan
          </Link>
        </div>

        <div className="price-card price-card-pro">
          <span className="price-badge">
            <Icon name="crown" size={12} />
            Pro
          </span>
          <div className="price-name">
            <Icon name="sparkles" size={15} />
            Twofold Pro
          </div>
          <div className="price-amount">
            ₹99 <span>/ 30 days</span>
          </div>
          <p className="price-note">
            Or ₹349 for a semester (180 days). Sandbox payment — no real money moves, ever.
          </p>
          <ul className="feature-list">
            {PRO_FEATURES.map((feature) => (
              <li key={feature}>
                <Icon name="check" size={15} strokeWidth={2.2} />
                {feature}
              </li>
            ))}
          </ul>
          <a href="#checkout" className="btn btn-primary btn-block">
            <Icon name="crown" size={15} />
            Upgrade with a test token
          </a>
        </div>
      </div>

      <section className="section" id="checkout">
        <div className="section-head">
          <h2 className="section-title">Checkout</h2>
          <p>Sandbox gateway — declines behave like a real card decline.</p>
        </div>
        <div className="pro-checkout">
          <ProPanel user={me} />
        </div>
      </section>
    </>
  )
}
