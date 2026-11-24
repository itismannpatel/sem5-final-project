package com.twofold.backend.domain;

/**
 * One review per (sessionId, fromUserId) — the UNIQUE pair is pre-checked in the
 * service layer so a duplicate stays a silent no-op that still answers 201.
 * Key order matches the Node contract: id, sessionId, fromUserId, toUserId,
 * rating, text, createdAt, role.
 */
public record Review(
    String id,
    String sessionId,
    String fromUserId,
    String toUserId,
    int rating,
    String text,
    String createdAt,
    String role) {}
