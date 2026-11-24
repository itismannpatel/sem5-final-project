package com.twofold.backend.store;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.ExchangeSkill;
import com.twofold.backend.domain.Message;
import com.twofold.backend.domain.Payment;
import com.twofold.backend.domain.Review;
import com.twofold.backend.domain.Session;
import com.twofold.backend.domain.Skill;
import com.twofold.backend.domain.User;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * SQLite-backed {@link Storage} (Task 1) — the local database. Replaces the
 * single-JSON-file store: rows are queryable, the file lives outside
 * {@code target/}, and a restart loads what was written instead of reseeding.
 *
 * <p>The snapshot contract survives unchanged: {@link #load()} returns the same
 * immutable {@link Database} the UI renders from and {@link #save(Database)}
 * replaces it in <b>one SQLite transaction</b> — all collections or none — so a
 * reader can never observe half a write. {@code StateService} still owns the
 * single-writer lock and the monotonic {@code version}.
 *
 * <p>Whole-document writes are deliberate: the API contract <em>is</em> a full
 * snapshot, so a partial-row update would have to be reconciled back into memory
 * anyway. At this size (hundreds of rows) one transaction is a few milliseconds;
 * the honest limit is recorded in {@code docs/DATA_MODEL.md}.
 *
 * <p>Path anchoring keeps the DEF-005/DEF-012 lesson: the database is resolved
 * from the module location, never the process working directory.
 */
public class JdbcStorage implements Storage {

  private static final Logger log = LoggerFactory.getLogger(JdbcStorage.class);

  private final Db db;
  private final ObjectMapper mapper;

  public JdbcStorage(Db db, ObjectMapper mapper) {
    this.db = db;
    this.mapper = mapper;
  }

  /** Convenience for tests: opens (and migrates) a database at {@code file}. */
  public JdbcStorage(Path file, ObjectMapper mapper) {
    this(new Db(file), mapper);
  }

  /**
   * Close the underlying connection. The application owns one shared
   * {@link Db}; tests that open a per-test file call this so Windows can delete
   * it (an open SQLite handle keeps the file and its WAL sidecars locked).
   */
  public void close() {
    db.close();
  }

  /**
   * The database file: {@code backend/twofold.db} for both an exploded run and
   * a packaged jar. Deliberately <b>outside</b> {@code target/} — a rebuild wipes
   * that directory, and runtime data must outlive it (Task 1, requirement 6).
   */
  public static Path defaultFile() {
    Path anchor = anchorDirectory();
    String name = anchor.getFileName() == null ? "" : anchor.getFileName().toString();
    Path base = "target".equals(name) && anchor.getParent() != null ? anchor.getParent() : anchor;
    return base.resolve("twofold.db");
  }

  /**
   * The directory runtime data belongs next to (never the process cwd —
   * DEF-005). Under {@code target/classes} or a jar in {@code target/} this is
   * {@code backend/target/}, whose parent is the backend module.
   */
  public static Path anchorDirectory() {
    // 1) Code source — exact for exploded runs (target/classes → target/).
    try {
      var source = JdbcStorage.class.getProtectionDomain().getCodeSource();
      if (source != null && source.getLocation() != null) {
        Path location = Path.of(source.getLocation().toURI());
        Path dir = location.getParent() != null ? location.getParent() : location;
        if (Files.isDirectory(dir)) {
          return dir;
        }
      }
    } catch (Exception e) {
      // Fat-jar nested URLs land here — that is what DEF-012 was about.
    }

    // 2) java.class.path — under `java -jar` this is the jar itself.
    try {
      String cp = System.getProperty("java.class.path", "");
      for (String entry : cp.split(File.pathSeparator)) {
        if (entry.isBlank()) {
          continue;
        }
        Path candidate = Path.of(entry);
        if (Files.exists(candidate)) {
          Path dir = candidate.getParent() != null ? candidate.getParent() : candidate;
          if (Files.isDirectory(dir)) {
            return dir;
          }
        }
      }
    } catch (Exception e) {
      // Keep going — temp is still better than cwd.
    }

    // 3) Last resort: per-user temp dir (never process-relative, DEF-005).
    return Path.of(System.getProperty("java.io.tmpdir"));
  }

  /**
   * One-way import of the retired JSON store (Task 1, requirement 3). Runs only
   * when the database is empty and the legacy file exists; afterwards the file
   * is renamed to {@code state.json.migrated} so it can never be re-imported
   * over newer rows. Returns how many rows were imported (0 = nothing to do).
   */
  public int migrateFromJson(Path legacyFile) {
    if (legacyFile == null || !Files.exists(legacyFile)) {
      return 0;
    }
    synchronized (db) {
      try (PreparedStatement ps = db.connection().prepareStatement("SELECT COUNT(*) FROM users");
          ResultSet rs = ps.executeQuery()) {
        if (rs.next() && rs.getInt(1) > 0) {
          log.info("twofold db: {} exists but the database is populated — not importing",
              legacyFile);
          return 0;
        }
      } catch (SQLException e) {
        throw new IllegalStateException("cannot inspect database", e);
      }
    }

    Database legacy;
    try {
      legacy = mapper.readValue(Files.readString(legacyFile, StandardCharsets.UTF_8),
          Database.class);
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException("cannot read legacy store " + legacyFile, e);
    }
    if (legacy == null) {
      return 0;
    }

    save(legacy);
    Path retired = legacyFile.resolveSibling(legacyFile.getFileName() + ".migrated");
    try {
      Files.move(legacyFile, retired, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("imported " + legacyFile + " but could not retire it", e);
    }
    int rows = legacy.users().size() + legacy.skills().size() + legacy.sessions().size()
        + legacy.reviews().size() + legacy.messages().size() + legacy.payments().size();
    log.info("twofold db: imported {} rows from {} (now {}.migrated)",
        rows, legacyFile, legacyFile.getFileName());
    return rows;
  }

  /* ------------------------------------------------------------------ load */

  @Override
  public Database load() {
    synchronized (db) {
      try {
        Integer version = readVersion();
        if (version == null) {
          return null;
        }
        return new Database(version, loadUsers(), loadSkills(), loadSessions(),
            loadReviews(), loadMessages(), loadPayments());
      } catch (SQLException e) {
        throw new IllegalStateException("cannot read database " + db.file(), e);
      }
    }
  }

  private Integer readVersion() throws SQLException {
    try (PreparedStatement ps = db.connection()
        .prepareStatement("SELECT value FROM meta WHERE key = 'version'")) {
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Integer.valueOf(rs.getString(1)) : null;
      }
    }
  }

  private List<User> loadUsers() throws SQLException {
    Map<String, List<ExchangeSkill>> teaches = new HashMap<>();
    Map<String, List<ExchangeSkill>> wants = new HashMap<>();
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT user_id, kind, skill_id, level FROM user_skills ORDER BY user_id, kind, position");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String kind = rs.getString("kind");
        ExchangeSkill entry = new ExchangeSkill(rs.getString("skill_id"), rs.getString("level"));
        (kind.equals("teaches") ? teaches : wants)
            .computeIfAbsent(rs.getString("user_id"), k -> new ArrayList<>())
            .add(entry);
      }
    }

    List<User> users = new ArrayList<>();
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT id, name, handle, email, branch, year, bio, availability, format, joined_at,"
            + " hue, verified FROM users");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String id = rs.getString("id");
        users.add(new User(
            id,
            rs.getString("name"),
            rs.getString("handle"),
            rs.getString("email"),
            rs.getString("branch"),
            rs.getString("year"),
            rs.getString("bio"),
            List.copyOf(teaches.getOrDefault(id, List.of())),
            List.copyOf(wants.getOrDefault(id, List.of())),
            availability(rs.getString("availability")),
            rs.getString("format"),
            rs.getString("joined_at"),
            rs.getInt("hue"),
            rs.getInt("verified") != 0));
      }
    }
    return users;
  }

  private List<String> availability(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    String[] values = mapper.readValue(json, String[].class);
    return List.of(values);
  }

  private List<Skill> loadSkills() throws SQLException {
    List<Skill> skills = new ArrayList<>();
    try (PreparedStatement ps = db.connection()
        .prepareStatement("SELECT id, name, category FROM skills");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        skills.add(new Skill(rs.getString("id"), rs.getString("name"), rs.getString("category")));
      }
    }
    return skills;
  }

  private List<Session> loadSessions() throws SQLException {
    Map<String, List<String>> slots = new LinkedHashMap<>();
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT session_id, slot FROM session_slots ORDER BY session_id, position");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        slots.computeIfAbsent(rs.getString("session_id"), k -> new ArrayList<>())
            .add(rs.getString("slot"));
      }
    }

    List<Session> sessions = new ArrayList<>();
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT id, teacher_id, learner_id, skill_id, topic, status, scheduled_for, created_at,"
            + " completed_at, note FROM sessions");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String id = rs.getString("id");
        sessions.add(new Session(
            id,
            rs.getString("teacher_id"),
            rs.getString("learner_id"),
            rs.getString("skill_id"),
            rs.getString("topic"),
            rs.getString("status"),
            List.copyOf(slots.getOrDefault(id, List.of())),
            rs.getString("scheduled_for"),
            rs.getString("created_at"),
            rs.getString("completed_at"),
            rs.getString("note")));
      }
    }
    return sessions;
  }

  private List<Review> loadReviews() throws SQLException {
    List<Review> reviews = new ArrayList<>();
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT id, session_id, from_user_id, to_user_id, rating, text, created_at, role"
            + " FROM reviews");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        reviews.add(new Review(
            rs.getString("id"),
            rs.getString("session_id"),
            rs.getString("from_user_id"),
            rs.getString("to_user_id"),
            rs.getInt("rating"),
            rs.getString("text"),
            rs.getString("created_at"),
            rs.getString("role")));
      }
    }
    return reviews;
  }

  private List<Message> loadMessages() throws SQLException {
    Map<String, List<String>> readBy = new HashMap<>();
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT message_id, user_id FROM message_reads ORDER BY message_id, position");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        readBy.computeIfAbsent(rs.getString("message_id"), k -> new ArrayList<>())
            .add(rs.getString("user_id"));
      }
    }

    List<Message> messages = new ArrayList<>();
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT id, from_user_id, to_user_id, body, created_at FROM messages");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        String id = rs.getString("id");
        messages.add(new Message(
            id,
            rs.getString("from_user_id"),
            rs.getString("to_user_id"),
            rs.getString("body"),
            rs.getString("created_at"),
            List.copyOf(readBy.getOrDefault(id, List.of()))));
      }
    }
    return messages;
  }

  private List<Payment> loadPayments() throws SQLException {
    List<Payment> payments = new ArrayList<>();
    try (PreparedStatement ps = db.connection().prepareStatement(
        "SELECT id, user_id, plan, amount_minor, currency, card_token, status, created_at"
            + " FROM payments");
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        payments.add(new Payment(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("plan"),
            rs.getInt("amount_minor"),
            rs.getString("currency"),
            rs.getString("card_token"),
            rs.getString("status"),
            rs.getString("created_at")));
      }
    }
    return payments;
  }

  /* ------------------------------------------------------------------ save */

  @Override
  public void save(Database state) {
    synchronized (db) {
      Connection c = db.connection();
      try {
        c.setAutoCommit(false);
        deleteAll(c);
        insertSkills(c, state.skills());
        insertUsers(c, state.users());
        insertUserSkills(c, state.users());
        insertSessions(c, state.sessions());
        insertSessionSlots(c, state.sessions());
        insertReviews(c, state.reviews());
        insertMessages(c, state.messages());
        insertMessageReads(c, state.messages());
        insertPayments(c, state.payments());
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO meta (key, value) VALUES ('version', ?)"
                + " ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
          ps.setString(1, String.valueOf(state.version()));
          ps.executeUpdate();
        }
        c.commit();
      } catch (SQLException e) {
        rollback(c);
        throw new IllegalStateException("cannot write database " + db.file(), e);
      } finally {
        autoCommit(c);
      }
    }
  }

  @Override
  public void clear() {
    synchronized (db) {
      Connection c = db.connection();
      try {
        c.setAutoCommit(false);
        deleteAll(c);
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM meta")) {
          ps.executeUpdate();
        }
        c.commit();
      } catch (SQLException e) {
        rollback(c);
        throw new IllegalStateException("cannot clear database " + db.file(), e);
      } finally {
        autoCommit(c);
      }
    }
  }

  /** Child rows first — foreign keys are on. Order is the reverse of insert. */
  private static void deleteAll(Connection c) throws SQLException {
    for (String table : List.of("message_reads", "messages", "reviews", "session_slots",
        "sessions", "payments", "user_skills", "users", "skills")) {
      try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + table)) {
        ps.executeUpdate();
      }
    }
  }

  private static void insertSkills(Connection c, List<Skill> skills) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO skills (id, name, category) VALUES (?, ?, ?)")) {
      for (Skill s : skills) {
        ps.setString(1, s.id());
        ps.setString(2, s.name());
        ps.setString(3, s.category());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private void insertUsers(Connection c, List<User> users) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO users (id, name, handle, email, branch, year, bio, availability, format,"
            + " joined_at, hue, verified) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      for (User u : users) {
        ps.setString(1, u.id());
        ps.setString(2, u.name());
        ps.setString(3, u.handle());
        ps.setString(4, u.email());
        ps.setString(5, u.branch());
        ps.setString(6, u.year());
        ps.setString(7, u.bio());
        ps.setString(8, mapper.writeValueAsString(u.availability()));
        ps.setString(9, u.format());
        ps.setString(10, u.joinedAt());
        ps.setInt(11, u.hue());
        ps.setInt(12, u.verified() ? 1 : 0);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void insertUserSkills(Connection c, List<User> users) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO user_skills (user_id, kind, skill_id, level, position)"
            + " VALUES (?, ?, ?, ?, ?)")) {
      for (User u : users) {
        batchSkills(ps, u.id(), "teaches", u.teaches());
        batchSkills(ps, u.id(), "wants", u.wants());
      }
      ps.executeBatch();
    }
  }

  private static void batchSkills(PreparedStatement ps, String userId, String kind,
      List<ExchangeSkill> entries) throws SQLException {
    for (int i = 0; i < entries.size(); i++) {
      ExchangeSkill entry = entries.get(i);
      ps.setString(1, userId);
      ps.setString(2, kind);
      ps.setString(3, entry.skillId());
      ps.setString(4, entry.level());
      ps.setInt(5, i);
      ps.addBatch();
    }
  }

  private static void insertSessions(Connection c, List<Session> sessions) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO sessions (id, teacher_id, learner_id, skill_id, topic, status,"
            + " scheduled_for, created_at, completed_at, note)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      for (Session s : sessions) {
        ps.setString(1, s.id());
        ps.setString(2, s.teacherId());
        ps.setString(3, s.learnerId());
        ps.setString(4, s.skillId());
        ps.setString(5, s.topic());
        ps.setString(6, s.status());
        ps.setString(7, s.scheduledFor());
        ps.setString(8, s.createdAt());
        ps.setString(9, s.completedAt());
        ps.setString(10, s.note());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void insertSessionSlots(Connection c, List<Session> sessions) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO session_slots (session_id, position, slot) VALUES (?, ?, ?)")) {
      for (Session s : sessions) {
        for (int i = 0; i < s.proposedSlots().size(); i++) {
          ps.setString(1, s.id());
          ps.setInt(2, i);
          ps.setString(3, s.proposedSlots().get(i));
          ps.addBatch();
        }
      }
      ps.executeBatch();
    }
  }

  private static void insertReviews(Connection c, List<Review> reviews) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO reviews (id, session_id, from_user_id, to_user_id, rating, text,"
            + " created_at, role) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      for (Review r : reviews) {
        ps.setString(1, r.id());
        ps.setString(2, r.sessionId());
        ps.setString(3, r.fromUserId());
        ps.setString(4, r.toUserId());
        ps.setInt(5, r.rating());
        ps.setString(6, r.text());
        ps.setString(7, r.createdAt());
        ps.setString(8, r.role());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void insertMessages(Connection c, List<Message> messages) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO messages (id, from_user_id, to_user_id, body, created_at)"
            + " VALUES (?, ?, ?, ?, ?)")) {
      for (Message m : messages) {
        ps.setString(1, m.id());
        ps.setString(2, m.fromUserId());
        ps.setString(3, m.toUserId());
        ps.setString(4, m.body());
        ps.setString(5, m.createdAt());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void insertMessageReads(Connection c, List<Message> messages)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO message_reads (message_id, position, user_id) VALUES (?, ?, ?)")) {
      for (Message m : messages) {
        for (int i = 0; i < m.readBy().size(); i++) {
          ps.setString(1, m.id());
          ps.setInt(2, i);
          ps.setString(3, m.readBy().get(i));
          ps.addBatch();
        }
      }
      ps.executeBatch();
    }
  }

  private static void insertPayments(Connection c, List<Payment> payments) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(
        "INSERT INTO payments (id, user_id, plan, amount_minor, currency, card_token, status,"
            + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      for (Payment p : payments) {
        ps.setString(1, p.id());
        ps.setString(2, p.userId());
        ps.setString(3, p.plan());
        ps.setInt(4, p.amountMinor());
        ps.setString(5, p.currency());
        ps.setString(6, p.cardToken());
        ps.setString(7, p.status());
        ps.setString(8, p.createdAt());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static void rollback(Connection c) {
    try {
      c.rollback();
    } catch (SQLException ignored) {
      // The original failure is the one worth reporting.
    }
  }

  private static void autoCommit(Connection c) {
    try {
      c.setAutoCommit(true);
    } catch (SQLException ignored) {
      // Nothing useful left to do here.
    }
  }
}
