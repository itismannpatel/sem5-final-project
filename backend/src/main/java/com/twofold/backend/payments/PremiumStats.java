package com.twofold.backend.payments;

import java.util.List;

/**
 * Store-wide revenue view — the numbers the e-commerce and funding documents
 * quote. Captured payments only: declines throw before they reach the ledger.
 *
 * <p>{@code byPlan} always contains every catalogue entry (count 0 when
 * nothing sold), in catalogue order, so clients and tests see a stable shape.
 */
public record PremiumStats(
    int payments,
    int revenueMinor,
    String currency,
    int activeSubscribers,
    List<PlanStat> byPlan) {

  public record PlanStat(String plan, int count, int revenueMinor) {}
}
