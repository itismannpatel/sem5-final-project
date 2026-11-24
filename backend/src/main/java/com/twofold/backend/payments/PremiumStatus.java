package com.twofold.backend.payments;

/**
 * One user's Twofold Pro standing, derived from the ledger (never stored —
 * there is no "premium" column to drift out of sync).
 *
 * <p>Null fields are serialized as JSON {@code null} so the key set is
 * always the same: {@code userId, active, plan, since, until, payments}.
 */
public record PremiumStatus(
    String userId,
    boolean active,
    String plan,
    String since,
    String until,
    int payments) {}
