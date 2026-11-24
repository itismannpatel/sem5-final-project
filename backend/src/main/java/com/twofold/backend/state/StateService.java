package com.twofold.backend.state;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.Message;
import com.twofold.backend.domain.Payment;
import com.twofold.backend.domain.Review;
import com.twofold.backend.domain.Session;
import com.twofold.backend.domain.Skill;
import com.twofold.backend.domain.User;
import com.twofold.backend.store.Storage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The one mutable thing in the process: the current snapshot, guarded by a
 * read/write lock and persisted through {@link Storage} after every change.
 *
 * <p>Boot semantics (Task 1): the database is the source of truth. A restart
 * <b>loads what was written</b> — it no longer reseeds — and the snapshot
 * version is preserved, so the demo now proves durability instead of resetting
 * itself. Seeding is a bootstrap concern: {@code seed.json} is written only when
 * the database has no state yet. {@code POST /api/reset} stays "wipe → reseed →
 * version + 1" for tests and local development; it is gated by
 * {@code twofold.reset.enabled} (off by default) and the UI has no reset control.
 *
 * <p>{@link #mutate(Function)} is the only write path: validation and the next
 * document are computed inside the write lock (so a check can never race a
 * concurrent write), and the result is persisted only when the function
 * produced a <em>new</em> document — which is how the no-op paths (duplicate
 * review, idempotent mark-read) stay bump-free, exactly like Node.
 */
@Service
public class StateService {

  private static final Logger log = LoggerFactory.getLogger(StateService.class);

  private final Storage storage;
  private final ObjectMapper mapper;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /** Written only under the write lock; read through {@link #snapshot()}. */
  private volatile Database state;

  public StateService(Storage storage, ObjectMapper mapper) {
    this.storage = storage;
    this.mapper = mapper;

    Database persisted = storage.load();
    Database boot;
    if (persisted == null) {
      boot = ordered(seedDocument().withVersion(1));
      storage.save(boot);
      log.info("twofold state: empty database — seeded from classpath:/seed.json");
    } else {
      boot = ordered(persisted);
    }
    this.state = boot;
    log.info("twofold state ready: v{} — {} users, {} skills, {} sessions",
        boot.version(), boot.users().size(), boot.skills().size(), boot.sessions().size());
  }

  /** The whole snapshot. Immutable, so it stays safe to serialize after unlock. */
  public Database snapshot() {
    lock.readLock().lock();
    try {
      return state;
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Apply a change under the write lock. The function both validates (throwing
   * {@link com.twofold.backend.http.ApiException} to abort without persisting)
   * and computes the next document; returning the same reference means "no-op —
   * don't persist, don't bump".
   */
  public Database mutate(Function<Database, Database> change) {
    lock.writeLock().lock();
    try {
      Database next = change.apply(state);
      if (next != state) {
        next = ordered(next);
        storage.save(next);
        state = next;
      }
      return state;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** Full reseed at current version + 1 (POST /api/reset — same path as boot). */
  public Database reset() {
    lock.writeLock().lock();
    try {
      Database next = ordered(seedDocument().withVersion(state.version() + 1));
      storage.save(next);
      state = next;
      return state;
    } finally {
      lock.writeLock().unlock();
    }
  }

  /** The version bump every successful write performs (Node: {@code bumpVersion}). */
  public static Database bump(Database db) {
    return db.withVersion(db.version() + 1);
  }

  /**
   * The contract's collection order, applied to every document we persist.
   * Users: name, case-insensitive ASC (Node: {@code COLLATE NOCASE} — ASCII
   * folding, which is what this comparator also does for the seeded names).
   * Skills: name binary ASC. Sessions/reviews: createdAt DESC. Messages:
   * createdAt ASC. Sorts are stable, so ties keep insertion order.
   */
  private static Database ordered(Database db) {
    List<User> users = new ArrayList<>(db.users());
    users.sort(Comparator.comparing((User u) -> u.name(), String.CASE_INSENSITIVE_ORDER));

    List<Skill> skills = new ArrayList<>(db.skills());
    skills.sort(Comparator.comparing((Skill s) -> s.name()));

    List<Session> sessions = new ArrayList<>(db.sessions());
    sessions.sort(Comparator.comparing((Session s) -> s.createdAt()).reversed());

    List<Review> reviews = new ArrayList<>(db.reviews());
    reviews.sort(Comparator.comparing((Review r) -> r.createdAt()).reversed());

    List<Message> messages = new ArrayList<>(db.messages());
    messages.sort(Comparator.comparing((Message m) -> m.createdAt()));

    // Payments: createdAt ASC — a ledger reads chronologically.
    List<Payment> payments = new ArrayList<>(db.payments());
    payments.sort(Comparator.comparing((Payment p) -> p.createdAt()));

    return new Database(db.version(), users, skills, sessions, reviews, messages, payments);
  }

  /** The classpath seed, version forced to 1 — the contract's starting stamp. */
  private Database seedDocument() {
    try (InputStream in = StateService.class.getResourceAsStream("/seed.json")) {
      if (in == null) {
        throw new IllegalStateException("seed.json is missing from the classpath");
      }
      byte[] json = in.readAllBytes();
      Database seed = mapper.readValue(json, Database.class).withVersion(1);
      // DEF-011: the seed's timestamps are authored, but the Node reference
      // regenerated them relative to process start. Shift whole days so the
      // demo never shows stale dates (and the reminder job finds work due).
      SeedMeta meta = mapper.readValue(json, SeedMeta.class);
      return SeedClock.shift(seed, meta.seededAt(), LocalDate.now());
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read classpath:/seed.json", e);
    }
  }

  /** Metadata keys in seed.json that are not part of the snapshot document. */
  private record SeedMeta(String seededAt) {}
}
