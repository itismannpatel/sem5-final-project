package com.twofold.backend.http;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The JavaScript semantics the Node API relied on, ported once so every handler
 * reads the same as the original:
 *
 * <ul>
 *   <li>{@link #str} / {@link #strArray} — verbatim copies of the helpers in
 *       {@code server/api.ts} (trim / keep only strings).</li>
 *   <li>{@link #jsNumber} — mirrors JS {@code Number()} for the shapes a JSON
 *       body can hold, so {@code rating} validation behaves identically.</li>
 *   <li>{@link #handleFor} — the exact handle derivation (lowercase, runs of
 *       non-alphanumerics → {@code .}, strip edge dots, fallback
 *       {@code student}).</li>
 *   <li>{@link #nowIso} / {@link #uid} — {@code Date.toISOString()} and
 *       {@code Math.random().toString(36).slice(2,11)} shapes.</li>
 * </ul>
 */
public final class Js {

  private Js() {}

  private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

  /** {@code new Date().toISOString()} — always millisecond precision, UTC. */
  private static final DateTimeFormatter ISO_MS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  /** {@code Math.random().toString(36).slice(2,11)}: 9 chars of [0-9a-z]. */
  public static String uid() {
    ThreadLocalRandom rnd = ThreadLocalRandom.current();
    StringBuilder sb = new StringBuilder(9);
    for (int i = 0; i < 9; i++) {
      sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
    }
    return sb.toString();
  }

  public static String nowIso() {
    return ISO_MS.format(Instant.now());
  }

  /** JS {@code str()} — only real strings survive, trimmed; anything else ''. */
  public static String str(Object value) {
    return value instanceof String s ? s.trim() : "";
  }

  /** JS {@code strArray()} — only real strings survive, order kept. */
  public static List<String> strArray(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().filter(v -> v instanceof String).map(v -> (String) v).toList();
  }

  /**
   * JS {@code Number()} for the shapes a JSON body can hold: numbers pass
   * through, {@code null} and {@code ''} are 0, booleans are 0/1, numeric
   * strings parse (trimmed), everything else is {@code NaN} — and {@code NaN}
   * is falsy, which is what the required-field checks rely on.
   */
  public static double jsNumber(Object value) {
    if (value == null) {
      return 0;
    }
    if (value instanceof Boolean b) {
      return b ? 1 : 0;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value instanceof String s) {
      String trimmed = s.trim();
      if (trimmed.isEmpty()) {
        return 0;
      }
      try {
        return Double.parseDouble(trimmed);
      } catch (NumberFormatException e) {
        return Double.NaN;
      }
    }
    return Double.NaN;
  }

  /** JS falsiness for numbers: {@code NaN} and 0 (incl. -0) are falsy. */
  public static boolean falsy(double value) {
    return Double.isNaN(value) || value == 0.0;
  }

  /**
   * The contract's handle rule: lowercase, every run of non-alphanumerics
   * becomes a single {@code .}, leading/trailing dots stripped, empty →
   * {@code student}. After the collapse step at most one edge dot exists on
   * each side, so the edge-strip regexes are equivalent to Node's
   * {@code /^\.|\.$/g}.
   */
  public static String handleFor(String name) {
    String handle = name.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", ".")
        .replaceAll("^\\.|\\.$", "");
    return handle.isEmpty() ? "student" : handle;
  }
}
