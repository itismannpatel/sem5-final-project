-- V1 — the six domain collections of docs/DATA_MODEL.md.
--
-- Ids stay the contract's strings (u_…, sk_…, s_…, r_…, m_…, p_…). Every
-- collection has a stable `position` where order is part of the JSON contract
-- (user skills, session slots, message read receipts). Everything else is read
-- back with the ordering StateService applies to the snapshot.
--
-- `meta` holds the snapshot `version` — the monotonic stamp the client polls.

CREATE TABLE meta (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

-- No seed row here on purpose: `meta.version` present is what tells the store
-- "this database already has a snapshot". Writing version 0 in the schema made
-- a fresh database look populated and skipped seeding entirely.

CREATE TABLE skills (
  id       TEXT PRIMARY KEY,
  name     TEXT NOT NULL,
  category TEXT NOT NULL
);

CREATE TABLE users (
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  handle       TEXT NOT NULL UNIQUE,
  email        TEXT NOT NULL COLLATE NOCASE UNIQUE,
  branch       TEXT NOT NULL,
  year         TEXT NOT NULL,
  bio          TEXT NOT NULL,
  availability TEXT NOT NULL DEFAULT '[]',   -- JSON array, order is the contract
  format       TEXT NOT NULL CHECK (format IN ('Online', 'On campus', 'Either')),
  joined_at    TEXT NOT NULL,
  hue          INTEGER NOT NULL,
  verified     INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE user_skills (
  user_id  TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  kind     TEXT NOT NULL CHECK (kind IN ('teaches', 'wants')),
  skill_id TEXT NOT NULL REFERENCES skills(id),
  level    TEXT NOT NULL CHECK (level IN ('Beginner', 'Intermediate', 'Advanced')),
  position INTEGER NOT NULL,
  PRIMARY KEY (user_id, kind, skill_id)
);

CREATE INDEX idx_user_skills_order ON user_skills (user_id, kind, position);

CREATE TABLE sessions (
  id            TEXT PRIMARY KEY,
  teacher_id    TEXT NOT NULL REFERENCES users(id),
  learner_id    TEXT NOT NULL REFERENCES users(id),
  skill_id      TEXT NOT NULL REFERENCES skills(id),
  topic         TEXT NOT NULL,
  status        TEXT NOT NULL
                CHECK (status IN ('proposed', 'confirmed', 'completed', 'declined', 'cancelled')),
  scheduled_for TEXT,
  created_at    TEXT NOT NULL,
  completed_at  TEXT,
  note          TEXT
);

CREATE INDEX idx_sessions_created ON sessions (created_at);

CREATE TABLE session_slots (
  session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  position   INTEGER NOT NULL,
  slot       TEXT NOT NULL,
  PRIMARY KEY (session_id, position)
);

CREATE TABLE reviews (
  id           TEXT PRIMARY KEY,
  session_id   TEXT NOT NULL REFERENCES sessions(id),
  from_user_id TEXT NOT NULL REFERENCES users(id),
  to_user_id   TEXT NOT NULL REFERENCES users(id),
  rating       INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
  text         TEXT NOT NULL,
  created_at   TEXT NOT NULL,
  role         TEXT NOT NULL CHECK (role IN ('taught', 'learned')),
  UNIQUE (session_id, from_user_id)
);

CREATE INDEX idx_reviews_created ON reviews (created_at);

CREATE TABLE messages (
  id           TEXT PRIMARY KEY,
  from_user_id TEXT NOT NULL REFERENCES users(id),
  to_user_id   TEXT NOT NULL REFERENCES users(id),
  body         TEXT NOT NULL,
  created_at   TEXT NOT NULL
);

CREATE INDEX idx_messages_created ON messages (created_at);

CREATE TABLE message_reads (
  message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
  position   INTEGER NOT NULL,
  user_id    TEXT NOT NULL,
  PRIMARY KEY (message_id, user_id)
);

CREATE TABLE payments (
  id           TEXT PRIMARY KEY,
  user_id      TEXT NOT NULL REFERENCES users(id),
  plan         TEXT NOT NULL,
  amount_minor INTEGER NOT NULL,
  currency     TEXT NOT NULL,
  card_token   TEXT NOT NULL,             -- stored masked (****4242)
  status       TEXT NOT NULL,
  created_at   TEXT NOT NULL
);

CREATE INDEX idx_payments_created ON payments (created_at);
