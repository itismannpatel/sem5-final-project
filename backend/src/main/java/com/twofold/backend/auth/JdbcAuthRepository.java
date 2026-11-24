package com.twofold.backend.auth;

import com.twofold.backend.store.Db;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * SQLite implementation of {@link AuthRepository} — same database file as the
 * snapshot, so a backup captures profiles, credentials and sessions together.
 *
 * <p>The {@link Db} lock serialises statements on the single connection; login
 * traffic is a handful of reads per session, so that is not a bottleneck.
 */
@Repository
public class JdbcAuthRepository implements AuthRepository {

  private final Db db;

  public JdbcAuthRepository(Db db) {
    this.db = db;
  }

  @Override
  public void saveAccount(String userId, String passwordHash) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO accounts (user_id, password_hash, created_at) VALUES (?, ?, ?)"
              + " ON CONFLICT(user_id) DO UPDATE SET password_hash = excluded.password_hash")) {
        ps.setString(1, userId);
        ps.setString(2, passwordHash);
        ps.setString(3, java.time.Instant.now().toString());
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("cannot store credentials for " + userId, e);
      }
    }
  }

  @Override
  public Optional<String> passwordHashFor(String userId) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection()
          .prepareStatement("SELECT password_hash FROM accounts WHERE user_id = ?")) {
        ps.setString(1, userId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        }
      } catch (SQLException e) {
        throw new IllegalStateException("cannot read credentials for " + userId, e);
      }
    }
  }

  @Override
  public Optional<String> userIdForLogin(String identifier) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT id FROM users WHERE email = ? COLLATE NOCASE OR handle = ? COLLATE NOCASE"
              + " LIMIT 1")) {
        ps.setString(1, identifier);
        ps.setString(2, identifier);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
        }
      } catch (SQLException e) {
        throw new IllegalStateException("cannot resolve login identifier", e);
      }
    }
  }

  @Override
  public boolean hasAccount(String userId) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection()
          .prepareStatement("SELECT 1 FROM accounts WHERE user_id = ?")) {
        ps.setString(1, userId);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next();
        }
      } catch (SQLException e) {
        throw new IllegalStateException("cannot check credentials for " + userId, e);
      }
    }
  }

  @Override
  public void insertSession(AuthSession session) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO auth_sessions (token, user_id, created_at, expires_at)"
              + " VALUES (?, ?, ?, ?)")) {
        ps.setString(1, session.token());
        ps.setString(2, session.userId());
        ps.setString(3, session.createdAt());
        ps.setString(4, session.expiresAt());
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("cannot create login session", e);
      }
    }
  }

  @Override
  public Optional<AuthSession> findSession(String token) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "SELECT token, user_id, created_at, expires_at FROM auth_sessions WHERE token = ?")) {
        ps.setString(1, token);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next()
              ? Optional.of(new AuthSession(rs.getString(1), rs.getString(2), rs.getString(3),
                  rs.getString(4)))
              : Optional.empty();
        }
      } catch (SQLException e) {
        throw new IllegalStateException("cannot read login session", e);
      }
    }
  }

  @Override
  public void touchSession(String token, String expiresAt) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection()
          .prepareStatement("UPDATE auth_sessions SET expires_at = ? WHERE token = ?")) {
        ps.setString(1, expiresAt);
        ps.setString(2, token);
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("cannot extend login session", e);
      }
    }
  }

  @Override
  public void deleteSession(String token) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection()
          .prepareStatement("DELETE FROM auth_sessions WHERE token = ?")) {
        ps.setString(1, token);
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("cannot end login session", e);
      }
    }
  }

  @Override
  public void deleteExpiredSessions(String nowIso) {
    synchronized (db) {
      try (PreparedStatement ps = db.connection()
          .prepareStatement("DELETE FROM auth_sessions WHERE expires_at < ?")) {
        ps.setString(1, nowIso);
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("cannot prune expired sessions", e);
      }
    }
  }

  @Override
  public void clearAccounts() {
    synchronized (db) {
      try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM accounts")) {
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("cannot clear credentials", e);
      }
    }
  }

  @Override
  public void clearSessions() {
    synchronized (db) {
      try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM auth_sessions")) {
        ps.executeUpdate();
      } catch (SQLException e) {
        throw new IllegalStateException("cannot clear sessions", e);
      }
    }
  }
}
