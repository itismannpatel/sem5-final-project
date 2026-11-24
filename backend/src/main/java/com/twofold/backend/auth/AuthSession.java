package com.twofold.backend.auth;

/**
 * A server-side login session (Task 2). The token is opaque, random and stored
 * in the {@code auth_sessions} table — the client only ever holds the cookie
 * value, never an identity it could assert for someone else.
 *
 * <p>Timestamps are ISO 8601 strings, like the rest of the contract.
 */
public record AuthSession(String token, String userId, String createdAt, String expiresAt) {}
