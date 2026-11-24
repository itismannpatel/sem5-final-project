package com.twofold.backend.payments;

import java.time.Instant;

/**
 * The seam between "the app wants money" and "a payment provider" (D4).
 *
 * <p>The application depends only on this interface; {@link SandboxGateway}
 * (test mode, no keys, no real money) is the shipped implementation. Swapping
 * in a real provider later means one new class — nothing else changes.
 */
public interface PaymentGateway {

  /**
   * Charge a plan.
   *
   * @throws PaymentFailedException if the provider refuses — never returns a
   *     partial or ambiguous result
   */
  PaymentReceipt charge(Plan plan, String cardToken) throws PaymentFailedException;

  /** What the provider confirmed: enough for the ledger to record a capture. */
  record PaymentReceipt(
      Plan plan,
      String cardToken,
      int amountMinor,
      String currency,
      Instant at) {}
}
