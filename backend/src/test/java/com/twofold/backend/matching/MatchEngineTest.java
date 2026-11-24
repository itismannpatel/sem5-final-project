package com.twofold.backend.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.ExchangeSkill;
import com.twofold.backend.domain.Review;
import com.twofold.backend.domain.Skill;
import com.twofold.backend.domain.User;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit level for the engine ported from {@code src/lib/matching.ts} (D3).
 * Weights: learn 45 / teach 35 / affinity 12 / trust 8; every signal saturates
 * at 2 skills (learn, teach) or 3 categories (affinity); trust falls back to
 * 0.6 with no reviews. Traces TC-044…TC-049 in {@code docs/testing/TEST_CASES.md}.
 */
class MatchEngineTest {

  private static final String P = "Programming";
  private static final String D = "Design";
  private static final String M = "Music";

  private static final List<Skill> CATALOGUE = List.of(
      new Skill("sk_a", "Alpha", P),
      new Skill("sk_b", "Bravo", D),
      new Skill("sk_c", "Charlie", M),
      new Skill("sk_d", "Delta", P));

  private static ExchangeSkill es(String id) {
    return new ExchangeSkill(id, "Intermediate");
  }

  private static User user(String id, String name, List<String> teaches, List<String> wants) {
    return new User(id, name, id, id + "@campus.edu", "CS", "2nd year", "bio",
        teaches.stream().map(MatchEngineTest::es).toList(),
        wants.stream().map(MatchEngineTest::es).toList(),
        List.of("Evenings"), "Either", "2026-01-01T00:00:00.000Z", 120, false);
  }

  private static Review review(String id, String to, int rating) {
    return new Review(id, "s1", "u_sender", to, rating, "text",
        "2026-01-01T00:00:00.000Z", "taught");
  }

  /** A database with the fixed catalogue and the supplied users/reviews. */
  private static Database db(List<User> users, List<Review> reviews) {
    return new Database(1, users, CATALOGUE, List.of(), reviews, List.of(), List.of());
  }

  /* ------------------------------------------------- TC-044 full overlap */

  @Test
  void fullOverlapScoresHundredWithEveryWeightMaxed() {
    User me = user("u_me", "Me", List.of("sk_c", "sk_d"), List.of("sk_a", "sk_b"));
    User them = user("u_them", "Them", List.of("sk_a", "sk_b"), List.of("sk_c", "sk_d"));
    // them teaches both of my wants, wants what I teach, shares 3 categories,
    // and has a 5.0 rating.
    Database database = db(List.of(me, them), List.of(review("r1", "u_them", 5)));

    MatchEngine.MatchResult match = MatchEngine.scoreMatch(me, them, database);

    assertThat(match.score()).isEqualTo(100);
    assertThat(match.breakdown()).isEqualTo(
        new MatchEngine.MatchBreakdown(45.0, 35.0, 12.0, 8.0));
    assertThat(MatchEngine.WEIGHT_LEARN + MatchEngine.WEIGHT_TEACH
        + MatchEngine.WEIGHT_AFFINITY + MatchEngine.WEIGHT_TRUST).isEqualTo(100);
  }

  /* --------------------------- TC-045 partial overlap + score rounding */

  @Test
  void partialOverlapRoundsTheUnroundedSum() {
    // One learn, one teach, one shared category, no reviews.
    User me = user("u_me", "Me", List.of("sk_unknown"), List.of("sk_a"));
    User them = user("u_them", "Them", List.of("sk_a"), List.of("sk_unknown"));
    Database database = db(List.of(me, them), List.of());

    MatchEngine.MatchResult match = MatchEngine.scoreMatch(me, them, database);

    assertThat(match.breakdown()).isEqualTo(
        new MatchEngine.MatchBreakdown(22.5, 17.5, 4.0, 4.8));
    // 22.5 + 17.5 + 4.0 + 4.8 = 48.8 → 49 (round of the *unrounded* sum).
    assertThat(match.score()).isEqualTo(49);
  }

  /* ------------------------------------------- TC-046 1 dp breakdown */

  @Test
  void breakdownRoundsToSix() {
    User me = user("u_me", "Me", List.of("sk_c", "sk_d"), List.of("sk_a", "sk_b"));
    User them = user("u_them", "Them", List.of("sk_a", "sk_b"), List.of("sk_c", "sk_d"));
    // 5 + 4 + 5 = 14 / 3 = 4.666… → average rounded to 1 dp = 4.7
    // trust = 4.7 / 5 * 8 = 7.52 → breakdown 7.5, score from 7.52.
    Database database = db(List.of(me, them), List.of(
        review("r1", "u_them", 5), review("r2", "u_them", 4), review("r3", "u_them", 5)));

    MatchEngine.MatchResult match = MatchEngine.scoreMatch(me, them, database);

    assertThat(match.breakdown().trust()).isEqualTo(7.5);
    assertThat(MatchEngine.ratingFor("u_them", database).average()).isEqualTo(4.7);
    // 45 + 35 + 12 + 7.52 = 99.52 → 100 (unrounded sum rounded once).
    assertThat(match.score()).isEqualTo(100);
  }

  /* ------------------------------------- TC-047 trust fallback with 0 reviews */

  @Test
  void trustFallsBackToNeutralSixtyPercentOnlyWithoutReviews() {
    User me = user("u_me", "Me", List.of("sk_c"), List.of("sk_a"));
    User fresh = user("u_fresh", "Fresh", List.of("sk_a"), List.of("sk_c"));
    User rated = user("u_rated", "Rated", List.of("sk_a"), List.of("sk_c"));
    Database database = db(List.of(me, fresh, rated), List.of(
        review("r1", "u_rated", 4), review("r2", "u_rated", 3)));

    assertThat(MatchEngine.ratingFor("u_fresh", database))
        .isEqualTo(new MatchEngine.Rating(0, 0));
    assertThat(MatchEngine.scoreMatch(me, fresh, database).breakdown().trust()).isEqualTo(4.8);

    // 3.5 / 5 * 8 = 5.6 — the fallback is never applied when count > 0.
    assertThat(MatchEngine.ratingFor("u_rated", database))
        .isEqualTo(new MatchEngine.Rating(3.5, 2));
    assertThat(MatchEngine.scoreMatch(me, rated, database).breakdown().trust()).isEqualTo(5.6);
  }

  /* ------------------------------- TC-048 filtering and mode=all */

  @Test
  void defaultModeFiltersZeroOverlapButAllModeKeepsEveryone() {
    User me = user("u_me", "Me", List.of("sk_c"), List.of("sk_a"));
    User overlap = user("u_overlap", "Overlap", List.of("sk_a"), List.of("sk_c"));
    User unrelated = user("u_unrelated", "Unrelated", List.of("sk_b"), List.of("sk_b"));
    Database database = db(List.of(me, overlap, unrelated), List.of());

    List<MatchEngine.MatchResult> ranked = MatchEngine.computeMatches(me, database);
    assertThat(ranked).extracting(m -> m.user().id()).containsExactly("u_overlap");

    List<MatchEngine.MatchResult> all = MatchEngine.scoreEveryone(me, database);
    assertThat(all).extracting(m -> m.user().id())
        .containsExactlyInAnyOrder("u_overlap", "u_unrelated");
    assertThat(all).noneMatch(m -> m.user().id().equals("u_me"));
    assertThat(all).filteredOn(m -> m.user().id().equals("u_unrelated"))
        .singleElement().satisfies(m -> assertThat(m.canLearn()).isEmpty());
  }

  /* --------------------------------- TC-049 ordering / tie-break */

  @Test
  void scoresDescendAndTiesBreakByNameDescending() {
    User me = user("u_me", "Me", List.of("sk_c"), List.of("sk_a"));
    // Both "Zara" and "Bea" fully overlap (same score); "Cal" only teaches me
    // something, so it ranks below both.
    User zara = user("u_zara", "Zara", List.of("sk_a"), List.of("sk_c"));
    User bea = user("u_bea", "Bea", List.of("sk_a"), List.of("sk_c"));
    User cal = user("u_cal", "Cal", List.of("sk_a"), List.of());
    Database database = db(List.of(me, zara, bea, cal), List.of());

    List<MatchEngine.MatchResult> ranked = MatchEngine.computeMatches(me, database);

    assertThat(ranked).extracting(m -> m.user().name())
        .containsExactly("Zara", "Bea", "Cal");
    assertThat(ranked.get(0).score()).isEqualTo(ranked.get(1).score());
    assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(2).score());
  }

  /* ------------------------------------------------ helpers / reasons */

  @Test
  void reciprocalFlagAndReasonsMatchTheReferenceWording() {
    User me = user("u_me", "Me", List.of("sk_c"), List.of("sk_a"));
    User them = user("u_them", "Them", List.of("sk_a"), List.of("sk_c"));
    User oneWay = user("u_oneway", "Oneway", List.of("sk_a"), List.of("sk_b"));
    Database database = db(List.of(me, them, oneWay), List.of());

    MatchEngine.MatchResult twoWay = MatchEngine.scoreMatch(me, them, database);
    assertThat(twoWay.reciprocal()).isTrue();
    assertThat(twoWay.reasons()).contains(
        "Teaches Alpha — on your wishlist",
        "Wants to learn Charlie — you teach it",
        "Two-way swap: you both gain something");

    MatchEngine.MatchResult one = MatchEngine.scoreMatch(me, oneWay, database);
    assertThat(one.reciprocal()).isFalse();
    assertThat(one.reasons()).contains("One-way: they have what you want");

    MatchEngine.MatchResult none = MatchEngine.scoreMatch(me,
        user("u_none", "None", List.of("sk_b"), List.of("sk_b")), database);
    assertThat(none.reasons()).containsExactly("Shared subject areas — worth a conversation");
  }

  @Test
  void highRatingReasonAppearsOnlyForThreeOrMoreReviewsAtFourPointFive() {
    User me = user("u_me", "Me", List.of("sk_c"), List.of("sk_a"));
    User them = user("u_them", "Them", List.of("sk_a"), List.of("sk_c"));
    List<Review> reviews = new ArrayList<>(List.of(
        review("r1", "u_them", 5), review("r2", "u_them", 5), review("r3", "u_them", 4)));

    MatchEngine.MatchResult match = MatchEngine.scoreMatch(me, them, db(List.of(me, them), reviews));

    assertThat(match.reasons()).anyMatch(r -> r.startsWith("Highly rated — 4.7 from 3 sessions"));
  }
}
