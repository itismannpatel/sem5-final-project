package com.twofold.backend.payments;

import org.springframework.stereotype.Component;

/**
 * Simulated payment provider — "test mode" for the e-commerce deliverable:
 * no signup, no API keys, no real money ever moves.
 *
 * <p>Deterministic on purpose, so integration tests can drive every outcome
 * without randomness, using Stripe-style test tokens:
 *
 * <ul>
 *   <li>{@code tok_decline…} → card declined
 *   <li>{@code tok_insufficient…} → insufficient funds
 *   <li>anything else → captured
 * </ul>
 */
@Component
public class SandboxGateway implements PaymentGateway {

  @Override
  public PaymentReceipt charge(Plan plan, String cardToken) throws PaymentFailedException {
    if (cardToken == null || cardToken.isBlank()) {
      throw new PaymentFailedException("payment failed: card token is required");
    }
    if (cardToken.startsWith("tok_decline")) {
      // Masked, like the ledger: a decline must never echo the presented token
      // back to the client (DEF-013).
      throw new PaymentFailedException(
          "payment failed: card " + PaymentService.mask(cardToken) + " was declined (sandbox)");
    }
    if (cardToken.startsWith("tok_insufficient")) {
      throw new PaymentFailedException("payment failed: insufficient funds (sandbox)");
    }
    return new PaymentReceipt(plan, cardToken, plan.amountMinor(), plan.currency(),
        java.time.Instant.now());
  }
}
