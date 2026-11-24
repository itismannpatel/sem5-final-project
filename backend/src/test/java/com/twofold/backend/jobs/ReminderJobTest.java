package com.twofold.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.Session;
import com.twofold.backend.state.StateService;
import com.twofold.backend.store.JdbcStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The multithreading deliverable's observable behaviour: window selection
 * {@code (now, now+window]}, per-slot {@code slot-proposed}, dedupe, the JSONL
 * outbox and the guarantee that a pass never moves {@code version}. Traces
 * TC-052…TC-058. The clock is not injectable (the job reads {@code Instant.now}),
 * so instants are built relative to now with wide margins — no flaky ±1 s
 * boundaries, and no absolute timestamps anywhere.
 */
class ReminderJobTest {

  private static final JsonMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();

  private static final int WINDOW_HOURS = 168;

  @TempDir
  Path tmp;

  private JdbcStorage store;
  private StateService state;
  private ReminderJob job;

  @BeforeEach
  void setUp() {
    store = new JdbcStorage(tmp.resolve("state.db"), MAPPER);
    state = new StateService(store, MAPPER);
    ReminderOutbox outbox = new ReminderOutbox(MAPPER, tmp.resolve("outbox.jsonl").toString());
    job = new ReminderJob(state, outbox, MAPPER, WINDOW_HOURS);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  private static String iso(Instant at) {
    return at.truncatedTo(ChronoUnit.MILLIS).toString();
  }

  private static Session session(String id, String status, String scheduledFor,
      List<String> slots) {
    return new Session(id, "u_aarav", "u_kabir", "sk_react", "topic " + id, status,
        slots, scheduledFor, "2026-11-21T08:00:00.000Z", null, null);
  }

  /**
   * Replace the seeded sessions with a controlled set. Reviews go too: they
   * reference sessions by foreign key, and these tests are about reminders, not
   * about orphans.
   */
  private void useSessions(List<Session> sessions) {
    state.mutate(db -> StateService.bump(
        db.withSessions(new ArrayList<>(sessions)).withReviews(List.of())));
  }

  private List<Map<String, Object>> outboxLines() throws IOException {
    Path file = tmp.resolve("outbox.jsonl");
    if (!Files.exists(file)) {
      return List.of();
    }
    List<Map<String, Object>> lines = new ArrayList<>();
    for (String line : Files.readAllLines(file)) {
      if (!line.isBlank()) {
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) MAPPER.readValue(line, Object.class);
        lines.add(parsed);
      }
    }
    return lines;
  }

  /* ----------------------------------------------- TC-052 window semantics */

  @Test
  void onlyFutureInstantsInsideTheWindowAreDue() {
    Instant now = Instant.now();
    useSessions(List.of(
        session("s_inside", "confirmed", iso(now.plus(1, ChronoUnit.HOURS)), List.of()),
        session("s_beyond", "confirmed", iso(now.plus(WINDOW_HOURS + 24, ChronoUnit.HOURS)),
            List.of()),
        session("s_past", "confirmed", iso(now.minus(1, ChronoUnit.HOURS)), List.of()),
        session("s_now", "confirmed", iso(now), List.of())));

    ReminderStats stats = job.runOnce();

    assertThat(stats.due()).isEqualTo(1);
    assertThat(stats.written()).isEqualTo(1);
    assertThat(stats.recent()).singleElement().asString().contains("s_inside");
  }

  /* ------------------------------------------- TC-053 slot-proposed rules */

  @Test
  void proposedSessionsEmitOneReminderPerInWindowSlot() {
    Instant now = Instant.now();
    useSessions(List.of(
        session("s_proposed", "proposed", null, List.of(
            iso(now.plus(2, ChronoUnit.HOURS)),
            iso(now.plus(3, ChronoUnit.DAYS)),
            iso(now.plus(30, ChronoUnit.DAYS)))),
        session("s_confirmed", "confirmed", iso(now.plus(4, ChronoUnit.HOURS)), List.of())));

    ReminderStats stats = job.runOnce();

    assertThat(stats.due()).isEqualTo(3);   // 2 slots + 1 confirmed session
    assertThat(stats.written()).isEqualTo(3);
    assertThat(stats.recent()).filteredOn(l -> l.contains("\"slot-proposed\""))
        .hasSize(2);
    assertThat(stats.recent()).filteredOn(l -> l.contains("s_proposed"))
        .allSatisfy(l -> assertThat(l).contains("\"kind\":\"slot-proposed\""));
  }

  /* ------------------------------------------------------- TC-054 dedupe */

  @Test
  void repeatingAPassDoesNotDuplicateLines() throws IOException {
    Instant now = Instant.now();
    useSessions(List.of(session("s_due", "confirmed", iso(now.plus(1, ChronoUnit.HOURS)),
        List.of())));

    ReminderStats first = job.runOnce();
    ReminderStats second = job.runOnce();

    assertThat(first.written()).isEqualTo(1);
    assertThat(second.due()).isEqualTo(1);        // still due…
    assertThat(second.written()).isEqualTo(1);    // …but nothing new was appended
    assertThat(second.runs()).isEqualTo(2);
    assertThat(outboxLines()).hasSize(1);
  }

  /* ---------------------------------------------- TC-055 read-only pass */

  @Test
  void aPassNeverBumpsTheVersionOrMutatesState() {
    Instant now = Instant.now();
    useSessions(List.of(session("s_due", "confirmed", iso(now.plus(1, ChronoUnit.HOURS)),
        List.of())));
    Database before = state.snapshot();

    job.runOnce();

    assertThat(state.snapshot().version()).isEqualTo(before.version());
    assertThat(state.snapshot().sessions()).isEqualTo(before.sessions());
  }

  /* ------------------------------------------- TC-056 outbox line shape */

  @Test
  void everyOutboxLineIsAStandaloneJsonObjectWithTheContractFields() throws IOException {
    Instant now = Instant.now();
    useSessions(List.of(session("s_due", "confirmed", iso(now.plus(1, ChronoUnit.HOURS)),
        List.of())));
    job.runOnce();

    List<Map<String, Object>> lines = outboxLines();
    assertThat(lines).hasSize(1);
    Map<String, Object> line = lines.get(0);
    assertThat(line).containsKeys("generatedAt", "kind", "sessionId", "at", "teacherId",
        "learnerId", "topic", "windowHours");
    assertThat(line.get("kind")).isEqualTo("session-soon");
    assertThat(line.get("sessionId")).isEqualTo("s_due");
    assertThat(line.get("at")).isEqualTo(iso(now.plus(1, ChronoUnit.HOURS)));
    assertThat(line.get("windowHours")).isEqualTo(WINDOW_HOURS);
  }

  /* ----------------------------------------------- TC-057 stats shape */

  @Test
  void statsReportTheLatestPassAndTheOutboxLocation() {
    Instant now = Instant.now();
    useSessions(List.of(session("s_due", "confirmed", iso(now.plus(1, ChronoUnit.HOURS)),
        List.of())));

    ReminderStats stats = job.runOnce();

    assertThat(stats.runs()).isGreaterThanOrEqualTo(1);
    assertThat(stats.lastRunAt())
        .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    assertThat(stats.windowHours()).isEqualTo(WINDOW_HOURS);
    assertThat(stats.outbox()).endsWith("outbox.jsonl");
    assertThat(stats.recent()).hasSize(1);
  }

  /* ----------------------------------------------- malformed stamps */

  @Test
  void malformedInstantsAreSkippedInsteadOfCrashingAPass() {
    useSessions(List.of(
        session("s_bad", "confirmed", "tomorrow at five", List.of()),
        session("s_bad_slot", "proposed", null, List.of("not-a-date"))));

    ReminderStats stats = job.runOnce();

    assertThat(stats.due()).isZero();
    assertThat(stats.written()).isZero();
    assertThat(stats.runs()).isEqualTo(1);
  }
}
