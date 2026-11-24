package com.twofold.backend.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.ExchangeSkill;
import com.twofold.backend.domain.Message;
import com.twofold.backend.domain.Payment;
import com.twofold.backend.domain.Review;
import com.twofold.backend.domain.Session;
import com.twofold.backend.domain.Skill;
import com.twofold.backend.domain.User;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The database half of the Java deliverable (Task 1): migrations, a full
 * round-trip of every collection, transactional overwrite, clear, durability
 * across a reopen, and the one-way import of the retired JSON store.
 *
 * <p>Replaces the old {@code JsonFileStoreTest} — same responsibilities, new
 * storage engine.
 */
class JdbcStorageTest {

  private static final JsonMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();

  @TempDir
  Path tmp;

  private static Database sample(int version) {
    User u = new User("u_a", "A", "a", "a@x", "CS", "1st year", "bio",
        List.of(new ExchangeSkill("sk_a", "Advanced"), new ExchangeSkill("sk_b", "Beginner")),
        List.of(new ExchangeSkill("sk_b", "Intermediate")),
        List.of("Weekday evenings", "Weekends"), "Either", "2026-01-01T00:00:00.000Z", 1, true);
    Skill a = new Skill("sk_a", "Alpha", "Code");
    Skill b = new Skill("sk_b", "Beta", "Design");
    Session s = new Session("s_a", "u_a", "u_a", "sk_a", "Topic", "proposed",
        List.of("2026-02-01T10:00:00.000Z", "2026-02-02T10:00:00.000Z"), null,
        "2026-01-02T00:00:00.000Z", null, "note");
    Review r = new Review("r_a", "s_a", "u_a", "u_a", 5, "great", "2026-01-03T00:00:00.000Z",
        "taught");
    Message m = new Message("m_a", "u_a", "u_a", "hello", "2026-01-04T00:00:00.000Z",
        List.of("u_a", "u_b"));
    Payment p = new Payment("p_a", "u_a", "pro_monthly", 9900, "INR", "****4242", "captured",
        "2026-01-05T00:00:00.000Z");
    return new Database(version, List.of(u), List.of(a, b), List.of(s), List.of(r), List.of(m),
        List.of(p));
  }

  @Test
  void freshDatabaseHasNoStateAndIsMigratedToTheLatestSchema() {
    Db db = new Db(tmp.resolve("empty.db"));
    JdbcStorage store = new JdbcStorage(db, MAPPER);

    assertThat(store.load()).isNull();
    assertThat(db.schemaVersion()).isEqualTo(2);
    db.close();
  }

  @Test
  void saveThenLoadRoundTripsEveryCollectionInOrder() {
    try (Db db = new Db(tmp.resolve("nested/twofold.db"))) {
      JdbcStorage store = new JdbcStorage(db, MAPPER);
      store.save(sample(42));

      Database loaded = store.load();
      assertThat(loaded).isNotNull();
      assertThat(loaded.version()).isEqualTo(42);
      assertThat(loaded.users()).isEqualTo(sample(42).users());
      assertThat(loaded.skills()).isEqualTo(sample(42).skills());
      assertThat(loaded.sessions()).isEqualTo(sample(42).sessions());
      assertThat(loaded.reviews()).isEqualTo(sample(42).reviews());
      assertThat(loaded.messages()).isEqualTo(sample(42).messages());
      assertThat(loaded.payments()).isEqualTo(sample(42).payments());
      // Order inside a collection is part of the contract, not incidental.
      assertThat(loaded.users().get(0).teaches().get(0).skillId()).isEqualTo("sk_a");
      assertThat(loaded.sessions().get(0).proposedSlots()).hasSize(2);
      assertThat(loaded.messages().get(0).readBy()).containsExactly("u_a", "u_b");
    }
  }

  @Test
  void saveReplacesTheWholeDocumentInOneTransaction() {
    try (Db db = new Db(tmp.resolve("state.db"))) {
      JdbcStorage store = new JdbcStorage(db, MAPPER);
      store.save(sample(1));
      store.save(sample(2));

      assertThat(store.load().version()).isEqualTo(2);
      // No accumulation: a rewrite, not an append.
      assertThat(store.load().users()).hasSize(1);
      assertThat(store.load().messages()).hasSize(1);
    }
  }

  @Test
  void dataSurvivesReopeningTheDatabaseFile() {
    Path file = tmp.resolve("durable.db");
    try (Db db = new Db(file)) {
      new JdbcStorage(db, MAPPER).save(sample(7));
    }
    try (Db reopened = new Db(file)) {
      Database loaded = new JdbcStorage(reopened, MAPPER).load();
      assertThat(loaded).isNotNull();
      assertThat(loaded.version()).isEqualTo(7);
      assertThat(loaded.users()).hasSize(1);
      assertThat(loaded.payments()).hasSize(1);
    }
  }

  @Test
  void clearDropsEverythingSoTheNextLoadIsEmpty() {
    try (Db db = new Db(tmp.resolve("state.db"))) {
      JdbcStorage store = new JdbcStorage(db, MAPPER);
      store.save(sample(1));

      store.clear();

      assertThat(store.load()).isNull();
      store.clear();   // idempotent
    }
  }

  @Test
  void legacyJsonStoreIsImportedOnceThenRetired() throws Exception {
    Path legacy = tmp.resolve("twofold-data/state.json");
    Files.createDirectories(legacy.getParent());
    Files.writeString(legacy, MAPPER.writeValueAsString(sample(9)));

    try (Db db = new Db(tmp.resolve("import.db"))) {
      JdbcStorage store = new JdbcStorage(db, MAPPER);
      int rows = store.migrateFromJson(legacy);

      assertThat(rows).isEqualTo(7);   // 1 user + 2 skills + session + review + message + payment
      assertThat(store.load().version()).isEqualTo(9);
      assertThat(store.load().users()).hasSize(1);
      assertThat(Files.exists(legacy)).isFalse();
      assertThat(Files.exists(Path.of(legacy + ".migrated"))).isTrue();
      // A populated database is never overwritten by a stale file.
      assertThat(store.migrateFromJson(legacy)).isZero();
    }
  }

  @Test
  void defaultDatabaseIsAnchoredOutsideTheProcessWorkingDirectory() {
    // DEF-005/DEF-012: never process.cwd() — the file lives beside the module.
    assertThat(JdbcStorage.defaultFile().isAbsolute()).isTrue();
    assertThat(JdbcStorage.defaultFile().getFileName().toString()).isEqualTo("twofold.db");
  }
}
