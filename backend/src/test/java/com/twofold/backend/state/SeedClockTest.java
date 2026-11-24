package com.twofold.backend.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.Message;
import com.twofold.backend.domain.Review;
import com.twofold.backend.domain.Session;
import com.twofold.backend.domain.Skill;
import com.twofold.backend.domain.User;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DEF-011 regression net (TEST_CASES TC-002): the seed's timestamps are authored
 * on 2026-11-21 but the Node reference regenerated them per boot, and the display
 * (and the reminder job) depend on that. The shift must move whole days while
 * preserving time-of-day, and must leave malformed/absent values alone.
 */
class SeedClockTest {

  private static Database seed() {
    User u = new User("u_a", "A", "a", "a@x", "CS", "1st year", "bio", List.of(), List.of(),
        List.of(), "Either", "2026-11-01T09:15:00.000Z", 10, false);
    Session s = new Session("s_a", "u_a", "u_b", "sk_a", "topic", "proposed",
        List.of("2026-11-27T10:30:00.000Z", "tomorrow at five"), null,
        "2026-11-21T08:00:00.000Z", null, null);
    Review r = new Review("r_a", "s_a", "u_a", "u_b", 5, "text",
        "2026-11-21T08:05:00.000Z", "taught");
    Message m = new Message("m_a", "u_a", "u_b", "hi", "2026-11-21T08:10:00.000Z",
        List.of("u_a"));
    return new Database(7, List.of(u), List.of(new Skill("sk_a", "A", "Cat")),
        List.of(s), List.of(r), List.of(m), List.of());
  }

  @Test
  void dayShiftIsZeroForTheAuthoredDay() {
    assertThat(SeedClock.dayShift("2026-11-21T12:00:00.000Z", LocalDate.of(2026, 11, 21)))
        .isZero();
    assertThat(SeedClock.dayShift("2026-11-21T12:00:00.000Z", LocalDate.of(2026, 11, 27)))
        .isEqualTo(6);
  }

  @Test
  void dayShiftIsDefensiveAboutMissingOrMalformedAnchors() {
    assertThat(SeedClock.dayShift(null, LocalDate.of(2026, 11, 22))).isZero();
    assertThat(SeedClock.dayShift(" ", LocalDate.of(2026, 11, 22))).isZero();
  }

  @Test
  void shiftMovesEveryTimestampAndPreservesTimeOfDay() {
    Database shifted = SeedClock.shift(seed(), "2026-11-21T12:00:00.000Z",
        LocalDate.of(2026, 11, 24));

    // +3 whole days: 27th 10:30Z → 30th 10:30Z, clock unchanged.
    assertThat(shifted.sessions().get(0).proposedSlots().get(0))
        .isEqualTo("2026-11-30T10:30:00.000Z");
    assertThat(shifted.sessions().get(0).createdAt()).isEqualTo("2026-11-24T08:00:00.000Z");
    assertThat(shifted.reviews().get(0).createdAt()).isEqualTo("2026-11-24T08:05:00.000Z");
    assertThat(shifted.messages().get(0).createdAt()).isEqualTo("2026-11-24T08:10:00.000Z");
    assertThat(shifted.users().get(0).joinedAt()).isEqualTo("2026-11-04T09:15:00.000Z");
    // The version and the skill catalogue are not time data.
    assertThat(shifted.version()).isEqualTo(7);
    assertThat(shifted.skills()).isEqualTo(seed().skills());
  }

  @Test
  void shiftLeavesMalformedStampsVerbatim() {
    Database shifted = SeedClock.shift(seed(), "2026-11-21T12:00:00.000Z",
        LocalDate.of(2026, 11, 22));

    assertThat(shifted.sessions().get(0).proposedSlots())
        .containsExactly("2026-11-28T10:30:00.000Z", "tomorrow at five");
    // Proposed sessions keep nulls absent (the JSON contract).
    assertThat(shifted.sessions().get(0).scheduledFor()).isNull();
    assertThat(shifted.sessions().get(0).completedAt()).isNull();
  }

  @Test
  void shiftIsANoOpOnTheAuthoredDay() {
    Database original = seed();
    assertThat(SeedClock.shift(original, "2026-11-21T12:00:00.000Z",
        LocalDate.of(2026, 11, 21))).isSameAs(original);
  }
}
