package com.twofold.backend.state;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.Message;
import com.twofold.backend.domain.Review;
import com.twofold.backend.domain.Session;
import com.twofold.backend.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * DEF-011: seed timestamps are <em>authored</em> (frozen in {@code seed.json}
 * on 2026-11-21), but the Node reference regenerated them relative to process
 * start ({@code server/seed.ts}: "Dates are computed relative to process
 * startup, so the app never shows a stale 'in 3 days'"). The frozen port would
 * show every session in the past a week after the seed was written — and would
 * starve the reminder job (DEF-012) of anything due.
 *
 * <p>The fix reproduces the reference behaviour with whole-day granularity: at
 * boot/reset, every timestamp in the seed shifts by
 * {@code days(today − seededAt's date)}, preserving time-of-day exactly (the
 * seed's local hours, the spacing between events, and the "3 days from now"
 * feel). Only the <em>date</em> of {@code seededAt} is used; the instant is
 * recorded for provenance.
 *
 * <p>Shifting is idempotent per document (a document that was already shifted
 * this boot is never shifted twice) because it runs exactly once, inside
 * {@link StateService#seedDocument()}.
 */
public final class SeedClock {

  /** Node {@code Date#toISOString}: {@code 2026-11-21T12:00:00.000Z}. */
  private static final DateTimeFormatter OUT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private SeedClock() {}

  /** Whole days between the seed's authored date and {@code today}. */
  public static long dayShift(String seededAt, LocalDate today) {
    if (seededAt == null || seededAt.isBlank()) {
      return 0;
    }
    LocalDate anchor = Instant.parse(seededAt).atZone(ZoneId.systemDefault()).toLocalDate();
    return ChronoUnit.DAYS.between(anchor, today);
  }

  /** The seed document with every timestamp moved forward by the day shift. */
  public static Database shift(Database db, String seededAt, LocalDate today) {
    long days = dayShift(seededAt, today);
    if (days == 0) {
      return db;
    }
    List<User> users = new ArrayList<>(db.users().size());
    for (User u : db.users()) {
      users.add(new User(u.id(), u.name(), u.handle(), u.email(), u.branch(), u.year(),
          u.bio(), u.teaches(), u.wants(), u.availability(), u.format(),
          shiftOne(u.joinedAt(), days), u.hue(), u.verified()));
    }

    List<Session> sessions = new ArrayList<>(db.sessions().size());
    for (Session s : db.sessions()) {
      List<String> slots = new ArrayList<>(s.proposedSlots().size());
      for (String slot : s.proposedSlots()) {
        slots.add(shiftOne(slot, days));
      }
      sessions.add(new Session(s.id(), s.teacherId(), s.learnerId(), s.skillId(), s.topic(),
          s.status(), slots, shiftOne(s.scheduledFor(), days), shiftOne(s.createdAt(), days),
          shiftOne(s.completedAt(), days), s.note()));
    }

    List<Review> reviews = new ArrayList<>(db.reviews().size());
    for (Review r : db.reviews()) {
      reviews.add(new Review(r.id(), r.sessionId(), r.fromUserId(), r.toUserId(), r.rating(),
          r.text(), shiftOne(r.createdAt(), days), r.role()));
    }

    List<Message> messages = new ArrayList<>(db.messages().size());
    for (Message m : db.messages()) {
      messages.add(new Message(m.id(), m.fromUserId(), m.toUserId(), m.body(),
          shiftOne(m.createdAt(), days), m.readBy()));
    }

    return new Database(db.version(), users, db.skills(), sessions, reviews, messages,
        db.payments());
  }

  /** {@code null} and malformed stamps pass through untouched. */
  static String shiftOne(String iso, long days) {
    if (iso == null || iso.isBlank()) {
      return iso;
    }
    try {
      return OUT.format(Instant.parse(iso).plus(Duration.ofDays(days)));
    } catch (RuntimeException e) {
      return iso;
    }
  }
}
