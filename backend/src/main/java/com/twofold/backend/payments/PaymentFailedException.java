package com.twofold.backend.payments;

/**
 * A sandbox charge was refused — declined card, insufficient funds, missing
 * token. This is the brief's named exception-handling example (decision D4).
 *
 * <p><b>Checked on purpose:</b> {@link PaymentGateway#charge} declares
 * {@code throws PaymentFailedException}, so every caller must deal with a
 * failed payment explicitly — there is no way to ignore it and pretend the
 * money arrived. {@code ApiExceptionHandler} maps it to the contract's
 * {@code 402 Payment Required} with an {@code {"error": ...}} body.
 */
public class PaymentFailedException extends Exception {

  public PaymentFailedException(String message) {
    super(message);
  }
}
