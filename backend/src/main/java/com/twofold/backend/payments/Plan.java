package com.twofold.backend.payments;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Optional;

/**
 * Twofold Pro's product catalogue — what the sandbox store sells, at what
 * price, for how long. Prices are minor units (paise) so no floating point
 * ever touches money; the e-commerce write-up quotes these figures as revenue.
 */
public enum Plan {

  PRO_MONTHLY("pro_monthly", 9900, "INR", 30),
  PRO_SEMESTER("pro_semester", 34900, "INR", 180);

  private final String code;
  private final int amountMinor;
  private final String currency;
  private final int days;

  Plan(String code, int amountMinor, String currency, int days) {
    this.code = code;
    this.amountMinor = amountMinor;
    this.currency = currency;
    this.days = days;
  }

  public String code() {
    return code;
  }

  public int amountMinor() {
    return amountMinor;
  }

  public String currency() {
    return currency;
  }

  public int days() {
    return days;
  }

  /** Lookup by API code; unknown codes are a client error, not a crash. */
  public static Optional<Plan> byCode(String code) {
    for (Plan plan : values()) {
      if (plan.code.equals(code)) {
        return Optional.of(plan);
      }
    }
    return Optional.empty();
  }

  /** Every valid code, pipe-joined — reused verbatim in the 400 message. */
  public static String expected() {
    return String.join("|", Arrays.stream(values()).map(Plan::code).toList());
  }

  /** When this plan stops being active if it was purchased at {@code at}. */
  public Instant expiresAt(Instant at) {
    return at.plus(days, ChronoUnit.DAYS);
  }
}
