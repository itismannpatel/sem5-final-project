package com.twofold.backend.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The JavaScript semantics the API contract was written against (Node parity).
 * These helpers decide whether a body field is "present", so they are tested
 * directly instead of only through HTTP — TC-015, TC-066/TC-067 trace here.
 */
class JsTest {

  @Test
  void strKeepsOnlyTrimmedStrings() {
    assertThat(Js.str("  hello ")).isEqualTo("hello");
    assertThat(Js.str("")).isEmpty();
    assertThat(Js.str(null)).isEmpty();
    assertThat(Js.str(42)).isEmpty();
    assertThat(Js.str(List.of("x"))).isEmpty();
  }

  @Test
  void strArrayKeepsStringsInOrderAndFiltersEverythingElse() {
    assertThat(Js.strArray(Arrays.asList("a", 1, "b", null, true))).containsExactly("a", "b");
    assertThat(Js.strArray("not-an-array")).isEmpty();
    assertThat(Js.strArray(null)).isEmpty();
    assertThat(Js.strArray(List.of(123))).isEmpty();
  }

  @Test
  void jsNumberMirrorsNumberCoercion() {
    assertThat(Js.jsNumber(5)).isEqualTo(5);
    assertThat(Js.jsNumber(4.5)).isEqualTo(4.5);
    assertThat(Js.jsNumber("4")).isEqualTo(4);
    assertThat(Js.jsNumber(" 4.5 ")).isEqualTo(4.5);
    assertThat(Js.jsNumber("")).isEqualTo(0);
    assertThat(Js.jsNumber(null)).isEqualTo(0);
    assertThat(Js.jsNumber(true)).isEqualTo(1);
    assertThat(Js.jsNumber(false)).isEqualTo(0);
    assertThat(Js.jsNumber("excellent")).isNaN();
    assertThat(Js.jsNumber(List.of(1))).isNaN();
  }

  @Test
  void falsyTreatsZeroAndNaNAsAbsent() {
    assertThat(Js.falsy(0)).isTrue();
    assertThat(Js.falsy(Double.NaN)).isTrue();
    assertThat(Js.falsy(1)).isFalse();
    assertThat(Js.falsy(0.5)).isFalse();
  }

  @Test
  void handleForLowercasesCollapsesRunsAndStripsEdgeDots() {
    assertThat(Js.handleFor("Aarav Mehta")).isEqualTo("aarav.mehta");
    assertThat(Js.handleFor("  Riya   Sharma  ")).isEqualTo("riya.sharma");
    assertThat(Js.handleFor("O'Brien")).isEqualTo("o.brien");
    assertThat(Js.handleFor("A/B Testing!")).isEqualTo("a.b.testing");
    assertThat(Js.handleFor("!!!")).isEqualTo("student");
    assertThat(Js.handleFor("")).isEqualTo("student");
  }

  @Test
  void uidIsNineLowercaseAlphanumerics() {
    String id = Js.uid();
    assertThat(id).hasSize(9).matches("[0-9a-z]{9}");
    // Not a guarantee, but 9 random chars colliding is vanishingly unlikely.
    assertThat(Js.uid()).isNotEqualTo(id);
  }

  @Test
  void nowIsoUsesMillisecondUtcPrecision() {
    assertThat(Js.nowIso()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
    assertThat(Arrays.stream(Js.nowIso().split("T")).count()).isEqualTo(2);
  }
}
