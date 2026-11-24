-- V2 — Task 2: real authentication. Kept in the same SQLite file so one backup
-- captures the whole local instance, but in its own migration so the schema
-- history is readable.

-- Deliberately NO foreign key to users: the snapshot store replaces every
-- collection on each write (delete + insert in one transaction), which would
-- cascade-delete live login sessions on every message. Credentials are keyed by
-- the same user id, and users are never deleted by the API, so a plain column is
-- both correct and keeps a signed-in tab signed in across writes.
CREATE TABLE accounts (
  user_id       TEXT PRIMARY KEY,
  password_hash TEXT NOT NULL,            -- BCrypt only, plaintext is never stored or logged
  created_at    TEXT NOT NULL
);

CREATE TABLE auth_sessions (
  token      TEXT PRIMARY KEY,            -- 256-bit random, base64url
  user_id    TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);
CREATE INDEX idx_auth_sessions_expiry ON auth_sessions (expires_at);
