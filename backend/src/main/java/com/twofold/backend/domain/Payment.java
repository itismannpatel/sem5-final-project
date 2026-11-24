package com.twofold.backend.domain;

/**
 * A captured sandbox payment for Twofold Pro (decision D4 — e-commerce revenue
 * story without real money).
 *
 * <p>Key order is the JSON contract: id, userId, plan, amountMinor, currency,
 * cardToken, status, createdAt. Only <em>captured</em> payments are persisted —
 * a decline throws {@code PaymentFailedException} before any state change, so
 * the ledger is revenue, not attempts (documented in the e-commerce write-up).
 *
 * <p>{@code cardToken} is stored masked ({@code ****4242}): the sandbox token
 * is never a PAN, and masking keeps the security checklist honest.
 */
public record Payment(
    String id,
    String userId,
    String plan,
    int amountMinor,
    String currency,
    String cardToken,
    String status,
    String createdAt) {}
