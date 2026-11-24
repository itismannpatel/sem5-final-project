package com.twofold.backend.auth;

import java.util.Optional;

/**
 * Credentials + session rows (Task 2). Kept behind an interface so the API
 * layer never imports JDBC, and so a cloud deployment could swap in a managed
 * database without touching {@link AuthService}.
 */
public interface AuthRepository {

  /** Attach a BCrypt hash to an existing user row. */
  void saveAccount(String userId, String passwordHash);

  /** The stored hash, or empty when the user has no credentials. */
  Optional<String> passwordHashFor(String userId);

  /** Resolve a login identifier — email or handle, case-insensitive. */
  Optional<String> userIdForLogin(String identifier);

  boolean hasAccount(String userId);

  void insertSession(AuthSession session);

  Optional<AuthSession> findSession(String token);

  /** Sliding expiry: extend an active session without re-issuing the token. */
  void touchSession(String token, String expiresAt);

  void deleteSession(String token);

  void deleteExpiredSessions(String nowIso);

  /** Reset support: credentials belong to profile rows, so a wipe drops both. */
  void clearAccounts();

  /** Reset support: every session refers to a profile that is about to disappear. */
  void clearSessions();
}
