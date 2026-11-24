package com.twofold.backend.matching;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.ExchangeSkill;
import com.twofold.backend.domain.Review;
import com.twofold.backend.domain.User;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The matching engine — a 1:1 port of {@code src/lib/matching.ts} (decision D3:
 * the engine moves server-side so every client scores identically, the work can
 * be cached per version, and the Java deliverable owns the scoring logic).
 *
 * <p>A match is scored on four signals, weighted to a 0–100 total:
 *
 * <pre>
 *   learn     45  how much of *your* wishlist this person can actually teach
 *   teach     35  how much of *their* wishlist you can teach (the reciprocity)
 *   affinity  12  shared subject areas, so adjacent interests surface too
 *   trust      8  their rating history
 * </pre>
 *
 * <p>The engine is pure: same inputs always produce the same score. All double
 * arithmetic mirrors the JS expression order exactly (including integer-count
 * divisions written as {@code / 2.0}), so scores match the reference to the
 * last digit.
 */
public final class MatchEngine {

  private MatchEngine() {}

  public static final int WEIGHT_LEARN = 45;
  public static final int WEIGHT_TEACH = 35;
  public static final int WEIGHT_AFFINITY = 12;
  public static final int WEIGHT_TRUST = 8;

  /** One signal's contribution, rounded to 1 dp like the reference. */
  public record MatchBreakdown(double learn, double teach, double affinity, double trust) {}

  public record MatchResult(
      User user,
      int score,
      MatchBreakdown breakdown,
      List<ExchangeSkill> canLearn,
      List<ExchangeSkill> canTeach,
      boolean reciprocal,
      List<String> reasons) {}

  /** A user's review standing: average rounded to 1 dp, plus how many. */
  public record Rating(double average, int count) {}

  /** Everyone else who can actually teach you something, ranked. */
  public static List<MatchResult> computeMatches(User me, Database db) {
    Collator collator = Collator.getInstance(Locale.ROOT); // ≈ JS localeCompare
    List<MatchResult> results = new ArrayList<>();
    for (User them : db.users()) {
      if (them.id().equals(me.id())) {
        continue;
      }
      MatchResult m = scoreMatch(me, them, db);
      if (!m.canLearn().isEmpty()) {
        results.add(m);
      }
    }
    results.sort((a, b) -> {
      int byScore = Integer.compare(b.score(), a.score());
      // JS: b.score - a.score || b.user.name.localeCompare(a.user.name)
      return byScore != 0 ? byScore : collator.compare(b.user().name(), a.user().name());
    });
    return results;
  }

  /** All other students with a score, including zero-overlap profiles. */
  public static List<MatchResult> scoreEveryone(User me, Database db) {
    List<MatchResult> results = new ArrayList<>();
    for (User them : db.users()) {
      if (!them.id().equals(me.id())) {
        results.add(scoreMatch(me, them, db));
      }
    }
    // Stable sort on score only — ties keep the snapshot's name order, as JS does.
    results.sort((a, b) -> Integer.compare(b.score(), a.score()));
    return results;
  }

  public static MatchResult scoreMatch(User me, User them, Database db) {
    Set<String> myWants = new HashSet<>();
    me.wants().forEach(s -> myWants.add(s.skillId()));
    Set<String> theirWants = new HashSet<>();
    them.wants().forEach(s -> theirWants.add(s.skillId()));

    List<ExchangeSkill> canLearn = them.teaches().stream()
        .filter(s -> myWants.contains(s.skillId())).toList();
    List<ExchangeSkill> canTeach = me.teaches().stream()
        .filter(s -> theirWants.contains(s.skillId())).toList();

    double learn = Math.min(1, canLearn.size() / 2.0) * WEIGHT_LEARN;
    double teach = Math.min(1, canTeach.size() / 2.0) * WEIGHT_TEACH;

    Set<String> mine = categorySet(me, db);
    Set<String> theirs = categorySet(them, db);
    long shared = mine.stream().filter(theirs::contains).count();
    double affinity = Math.min(1, shared / 3.0) * WEIGHT_AFFINITY;

    Rating rating = ratingFor(them.id(), db);
    double trustValue = rating.count() > 0 ? rating.average() / 5 : 0.6;
    double trust = trustValue * WEIGHT_TRUST;

    MatchBreakdown breakdown = new MatchBreakdown(
        round1(learn), round1(teach), round1(affinity), round1(trust));

    int score = (int) Math.round(learn + teach + affinity + trust);
    boolean reciprocal = !canLearn.isEmpty() && !canTeach.isEmpty();

    List<String> reasons = new ArrayList<>();
    if (!canLearn.isEmpty()) {
      reasons.add("Teaches " + names(canLearn, db) + " — on your wishlist");
    }
    if (!canTeach.isEmpty()) {
      reasons.add("Wants to learn " + names(canTeach, db) + " — you teach it");
    }
    if (reciprocal) {
      reasons.add("Two-way swap: you both gain something");
    } else if (!canLearn.isEmpty()) {
      reasons.add("One-way: they have what you want");
    }
    if (rating.count() >= 3 && rating.average() >= 4.5) {
      reasons.add(String.format(Locale.ROOT, "Highly rated — %.1f from %d sessions",
          rating.average(), rating.count()));
    }
    if (reasons.isEmpty()) {
      reasons.add("Shared subject areas — worth a conversation");
    }

    return new MatchResult(them, score, breakdown, canLearn, canTeach, reciprocal, reasons);
  }

  /** Averages ALL reviews where the user is the recipient, to 1 dp (JS ratingFor). */
  public static Rating ratingFor(String userId, Database db) {
    List<Review> reviews = db.reviews().stream()
        .filter(r -> r.toUserId().equals(userId)).toList();
    if (reviews.isEmpty()) {
      return new Rating(0, 0);
    }
    int total = reviews.stream().mapToInt(Review::rating).sum();
    double average = round1((double) total / reviews.size());
    return new Rating(average, reviews.size());
  }

  /* ------------------------------------------------------------- helpers */

  private static double round1(double value) {
    return Math.round(value * 10) / 10.0;
  }

  /** {@code SKILL_BY_ID[id]?.name ?? id} — catalogue lookup with id fallback. */
  private static String skillName(String id, Database db) {
    return db.skills().stream().filter(s -> s.id().equals(id)).findFirst()
        .map(s -> s.name()).orElse(id);
  }

  private static String skillCategory(String id, Database db) {
    return db.skills().stream().filter(s -> s.id().equals(id)).findFirst()
        .map(s -> s.category()).orElse(null);
  }

  /** "A, B and C" — or a single name, or '' for an empty list (JS join semantics). */
  private static String names(List<ExchangeSkill> skills, Database db) {
    List<String> list = skills.stream().map(s -> skillName(s.skillId(), db)).toList();
    if (list.size() <= 1) {
      return String.join("", list);
    }
    return String.join(", ", list.subList(0, list.size() - 1)) + " and "
        + list.get(list.size() - 1);
  }

  /** Every subject category this user touches (teaches + wants). */
  private static Set<String> categorySet(User user, Database db) {
    Set<String> set = new HashSet<>();
    for (ExchangeSkill s : user.teaches()) {
      String category = skillCategory(s.skillId(), db);
      if (category != null) {
        set.add(category);
      }
    }
    for (ExchangeSkill s : user.wants()) {
      String category = skillCategory(s.skillId(), db);
      if (category != null) {
        set.add(category);
      }
    }
    return set;
  }
}
