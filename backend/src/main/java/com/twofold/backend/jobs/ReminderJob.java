package com.twofold.backend.jobs;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.Session;
import com.twofold.backend.http.Js;
import com.twofold.backend.state.StateService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The multithreading deliverable: a {@code @Scheduled} background pass that
 * runs <b>off the HTTP threads</b> (its own pool — see
 * {@link com.twofold.backend.config.SchedulingConfig}), scans the snapshot
 * for sessions starting soon, and appends reminders to the outbox file.
 *
 * <p><b>Read-only against API state.</b> It takes a snapshot, computes due
 * reminders, and appends to a file — it never mutates {@code Database}, so
 * {@code version} never moves because of it (verified by the smoke suite and
 * the concurrency tests).
 *
 * <p><b>Rules</b> (window = {@code twofold.reminder.window-hours}, default
 * 7 days ahead of now):
 *
 * <ul>
 *   <li>{@code session-soon} — a {@code confirmed} session whose
 *       {@code scheduledFor} is inside the window</li>
 *   <li>{@code slot-proposed} — each slot of a {@code proposed} session
 *       inside the window</li>
 * </ul>
 *
 * <p><b>Dedupe</b> is per process: each {@code kind|session|instant} is
 * emitted once, so the 60-second cadence never re-announces the same slot.
 * (A fresh boot starts a fresh dedupe set — and DEF-011's date shift gives a
 * fresh boot fresh dates — so each demo run produces its own block of lines.)
 */
@Service
public class ReminderJob {

  private static final Logger log = LoggerFactory.getLogger(ReminderJob.class);

  private final StateService state;
  private final ReminderOutbox outbox;
  private final ObjectMapper mapper;
  private final int windowHours;

  private final Set<String> emitted = new HashSet<>();
  private final AtomicLong runs = new AtomicLong();
  private final AtomicLong writtenTotal = new AtomicLong();
  private volatile int dueLastRun;
  private volatile String lastRunAt;

  public ReminderJob(StateService state, ReminderOutbox outbox, ObjectMapper mapper,
      @Value("${twofold.reminder.window-hours:168}") int windowHours) {
    this.state = state;
    this.outbox = outbox;
    this.mapper = mapper;
    this.windowHours = windowHours;
  }

  /**
   * The scheduled cadence. Failures are logged, never propagated — a background
   * job must not take the web process down.
   */
  @Scheduled(fixedDelayString = "${twofold.reminder.delay-ms:60000}",
      initialDelayString = "${twofold.reminder.initial-delay-ms:15000}")
  public void scheduled() {
    try {
      runOnce();
    } catch (RuntimeException e) {
      log.error("[twofold reminders] pass failed", e);
    }
  }

  /**
   * One pass — the seam tests call directly (no sleeping on the scheduler).
   * {@code synchronized}: the scheduler thread and a test thread can both ask,
   * and the dedupe set must not interleave.
   */
  public synchronized ReminderStats runOnce() {
    Database db = state.snapshot();
    Instant now = Instant.now();
    Instant horizon = now.plus(Duration.ofHours(windowHours));

    List<Reminder> due = new ArrayList<>();
    for (Session s : db.sessions()) {
      if ("confirmed".equals(s.status())) {
        if (within(s.scheduledFor(), now, horizon)) {
          due.add(new Reminder("session-soon", s, s.scheduledFor()));
        }
      } else if ("proposed".equals(s.status())) {
        for (String slot : s.proposedSlots()) {
          if (within(slot, now, horizon)) {
            due.add(new Reminder("slot-proposed", s, slot));
          }
        }
      }
    }

    List<String> lines = new ArrayList<>();
    for (Reminder r : due) {
      if (emitted.add(r.key())) {
        lines.add(render(r));
      }
    }

    int appended = outbox.append(lines);
    dueLastRun = due.size();
    writtenTotal.addAndGet(appended);
    long run = runs.incrementAndGet();
    lastRunAt = Js.nowIso();
    if (appended > 0) {
      log.info("[twofold reminders] run #{}: {} due, {} new → {}", run, due.size(), appended,
          outbox.path());
    }
    return stats();
  }

  /** Current stats for {@code GET /api/reminders}. */
  public ReminderStats stats() {
    return new ReminderStats(runs.get(), dueLastRun, writtenTotal.get(), lastRunAt, windowHours,
        outbox.path(), outbox.recent());
  }

  /* ------------------------------------------------------------- helpers */

  /** Inside (now, horizon]: strictly future, at most {@code windowHours} out. */
  private static boolean within(String iso, Instant now, Instant horizon) {
    if (iso == null || iso.isBlank()) {
      return false;
    }
    try {
      Instant at = Instant.parse(iso);
      return at.isAfter(now) && !at.isAfter(horizon);
    } catch (RuntimeException e) {
      return false;   // malformed stamp → not due, never crash a pass
    }
  }

  /** One JSONL line: key order fixed by the LinkedHashMap. */
  private String render(Reminder r) {
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("generatedAt", Js.nowIso());
    line.put("kind", r.kind());
    line.put("sessionId", r.session().id());
    line.put("at", r.at());
    line.put("teacherId", r.session().teacherId());
    line.put("learnerId", r.session().learnerId());
    line.put("topic", r.session().topic());
    line.put("windowHours", windowHours);
    return mapper.writeValueAsString(line);
  }

  /** One due reminder: {@code kind|sessionId@instant} is the dedupe key. */
  private record Reminder(String kind, Session session, String at) {
    String key() {
      return kind + "|" + session().id() + "@" + at();
    }
  }
}
