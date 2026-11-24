package com.twofold.backend.auth;

import com.twofold.backend.http.ApiException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Real authentication (Task 2): BCrypt credentials, opaque server-side session
 * tokens, 401 when there is no valid session.
 *
 * <p><b>Password handling.</b> Only a BCrypt hash is stored ({@code accounts}
 * table); a plaintext password never reaches the database, the snapshot or a log
 * line. Login answers the same 401 message for an unknown account and a wrong
 * password, so it does not confirm which emails exist.
 *
 * <p><b>Sessions.</b> A 256-bit random token (base64url) is stored in
 * {@code auth_sessions} with a 7-day expiry and delivered as an <b>HttpOnly
 * cookie</b>. HttpOnly keeps the token out of JavaScript (an XSS cannot read it)
 * and {@code SameSite=Lax} stops cross-site POSTs from riding the cookie, so no
 * CSRF token is needed for this same-origin app. A bearer token would have been
 * marginally easier for scripts to carry, but it has to live in JS-readable
 * storage — a worse trade. The expiry slides while the user is active.
 *
 * <p><b>No bootstrap credentials (Task 3).</b> The seeded students are profiles
 * and demo content only; they deliberately have no {@code accounts} row, so
 * login against them fails forever. The only accounts that exist are the ones
 * created through {@code /api/auth/register}. There is no shared password, no
 * demo account list and nothing for a deployment to leak.
 */
@Service
public class AuthService {

  private static final Logger log = LoggerFactory.getLogger(AuthService.class);

  public static final String COOKIE_NAME = "twofold_session";
  public static final Duration SESSION_TTL = Duration.ofDays(7);

  private static final int MIN_PASSWORD = 8;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final AuthRepository repo;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  public AuthService(AuthRepository repo) {
    this.repo = repo;
  }

  /** Attach credentials to a freshly registered profile. */
  public void createAccount(String userId, String password) {
    if (password == null || password.length() < MIN_PASSWORD) {
      throw ApiException.badRequest("password must be at least " + MIN_PASSWORD + " characters");
    }
    repo.saveAccount(userId, encoder.encode(password));
  }

  /**
   * Verify a credential pair and open a session. The identifier may be an email
   * or a handle (case-insensitive) — the UI accepts either.
   */
  public AuthSession login(String identifier, String password) {
    String cleanId = identifier == null ? "" : identifier.trim();
    String cleanPassword = password == null ? "" : password;
    if (cleanId.isEmpty() || cleanPassword.isEmpty()) {
      throw ApiException.unauthorized("invalid email or password");
    }
    Optional<String> userId = repo.userIdForLogin(cleanId);
    if (userId.isPresent()) {
      Optional<String> hash = repo.passwordHashFor(userId.get());
      if (hash.isPresent() && encoder.matches(cleanPassword, hash.get())) {
        return issue(userId.get());
      }
    }
    throw ApiException.unauthorized("invalid email or password");
  }

  /** Mint a token, store it, and prune anything that expired along the way. */
  public AuthSession issue(String userId) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant now = Instant.now();
    AuthSession session = new AuthSession(token, userId, now.toString(),
        now.plus(SESSION_TTL).toString());
    repo.insertSession(session);
    repo.deleteExpiredSessions(now.toString());
    return session;
  }

  /**
   * The actor for a request, or {@code null}. Expired sessions resolve to null
   * and are deleted; an active session past half its lifetime is extended
   * (sliding expiry) without rotating the token.
   */
  public String userIdFor(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    Optional<AuthSession> found = repo.findSession(token);
    if (found.isEmpty()) {
      return null;
    }
    AuthSession session = found.get();
    Instant now = Instant.now();
    Instant expires;
    try {
      expires = Instant.parse(session.expiresAt());
    } catch (RuntimeException e) {
      repo.deleteSession(token);
      return null;
    }
    if (!expires.isAfter(now)) {
      repo.deleteSession(token);
      return null;
    }
    if (expires.isBefore(now.plus(SESSION_TTL.dividedBy(2)))) {
      repo.touchSession(token, now.plus(SESSION_TTL).toString());
    }
    return session.userId();
  }

  /** Sign out: the token is gone server-side, so replaying the cookie fails. */
  public void logout(String token) {
    if (token != null && !token.isBlank()) {
      repo.deleteSession(token);
    }
  }

  /**
   * Reset support: the profile rows are about to be replaced by the seed, so
   * their credentials and live sessions go with them. Nothing is re-created —
   * the seeded students have no passwords by design (Task 3).
   */
  public void clearAllCredentials() {
    repo.clearSessions();
    repo.clearAccounts();
    log.info("twofold auth: credentials and sessions cleared by reset");
  }
}
