package com.twofold.backend.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.Payment;
import com.twofold.backend.http.ApiException;
import com.twofold.backend.http.Js;
import com.twofold.backend.state.StateService;
import com.twofold.backend.store.JdbcStorage;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sandbox premium flow (D4) — the assignment's custom-exception and
 * e-commerce-payment case. Traces TC-033…TC-043: catalogue, gateway outcomes,
 * 402-with-no-state-change, masked ledger rows, derived subscription status and
 * the stats read model.
 *
 * <p>Runs against a real {@link StateService} with a temp-dir file store, so the
 * write path (lock + persist + version) is exercised, not mocked.
 */
class PaymentServiceTest {

  private static final JsonMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();

  @TempDir
  Path tmp;

  private JdbcStorage store;
  private StateService state;
  private PaymentService payments;

  @BeforeEach
  void setUp() {
    store = new JdbcStorage(tmp.resolve("state.db"), MAPPER);
    state = new StateService(store, MAPPER);
    payments = new PaymentService(state, new SandboxGateway());
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  /* ------------------------------------------------------- catalogue */

  @Test
  void cataloguePinsPricesInMinorUnits() {
    assertThat(Plan.PRO_MONTHLY.code()).isEqualTo("pro_monthly");
    assertThat(Plan.PRO_MONTHLY.amountMinor()).isEqualTo(9900);
    assertThat(Plan.PRO_MONTHLY.days()).isEqualTo(30);
    assertThat(Plan.PRO_SEMESTER.amountMinor()).isEqualTo(34900);
    assertThat(Plan.PRO_SEMESTER.days()).isEqualTo(180);
    assertThat(Plan.expected()).isEqualTo("pro_monthly|pro_semester");
    assertThat(Plan.byCode("pro_yearly")).isEmpty();
  }

  @Test
  void planExpiryAddsItsDuration() {
    Instant at = Instant.parse("2026-11-22T10:00:00Z");
    assertThat(Plan.PRO_MONTHLY.expiresAt(at)).isEqualTo(at.plus(30, ChronoUnit.DAYS));
    assertThat(Plan.PRO_SEMESTER.expiresAt(at)).isEqualTo(at.plus(180, ChronoUnit.DAYS));
  }

  /* -------------------------------------------------------- gateway */

  @Test
  void sandboxGatewayOutcomesAreDeterministic() throws Exception {
    SandboxGateway gateway = new SandboxGateway();
    PaymentGateway.PaymentReceipt receipt = gateway.charge(Plan.PRO_MONTHLY, "tok_ok_4242");
    assertThat(receipt.amountMinor()).isEqualTo(9900);
    assertThat(receipt.currency()).isEqualTo("INR");

    // DEF-013: a decline names the masked token, never the presented one.
    assertThatThrownBy(() -> gateway.charge(Plan.PRO_MONTHLY, "tok_decline_4242"))
        .isInstanceOf(PaymentFailedException.class)
        .hasMessageContaining("declined")
        .hasMessageContaining("****4242")
        .hasMessageNotContaining("tok_decline_4242");
    assertThatThrownBy(() -> gateway.charge(Plan.PRO_MONTHLY, "tok_insufficient_1"))
        .isInstanceOf(PaymentFailedException.class)
        .hasMessageContaining("insufficient funds");
    assertThatThrownBy(() -> gateway.charge(Plan.PRO_MONTHLY, "  "))
        .isInstanceOf(PaymentFailedException.class)
        .hasMessageContaining("card token is required");
  }

  /* ------------------------------------------------------- purchase */

  @Test
  void successfulPurchaseAppendsOneMaskedLedgerRowAndBumpsOnce() throws Exception {
    int before = state.snapshot().version();

    Database after = payments.purchase("u_aarav", "pro_monthly", "tok_ok_4242");

    assertThat(after.version()).isEqualTo(before + 1);
    assertThat(after.payments()).hasSize(1);
    Payment p = after.payments().get(0);
    assertThat(p.id()).startsWith("pay_");
    assertThat(p.userId()).isEqualTo("u_aarav");
    assertThat(p.plan()).isEqualTo("pro_monthly");
    assertThat(p.amountMinor()).isEqualTo(9900);
    assertThat(p.currency()).isEqualTo("INR");
    assertThat(p.status()).isEqualTo("captured");
    // The raw token never reaches the ledger.
    assertThat(p.cardToken()).isEqualTo("****4242");
    assertThat(p.createdAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
  }

  @Test
  void semesterPurchaseUsesItsOwnPrice() throws Exception {
    Database after = payments.purchase("u_riya", "pro_semester", "tok_ok");
    assertThat(after.payments().get(0).amountMinor()).isEqualTo(34900);
    assertThat(after.payments().get(0).cardToken()).isEqualTo("****");
  }

  @Test
  void declineIsCheckedExceptionBeforeAnyStateChange() {
    int before = state.snapshot().version();

    assertThatThrownBy(() -> payments.purchase("u_aarav", "pro_monthly", "tok_decline_1"))
        .isInstanceOf(PaymentFailedException.class);

    Database after = state.snapshot();
    assertThat(after.version()).isEqualTo(before);
    assertThat(after.payments()).isEmpty();
  }

  @Test
  void insufficientFundsIsAlsoA402AndLeavesNoRow() {
    assertThatThrownBy(() -> payments.purchase("u_aarav", "pro_monthly", "tok_insufficient_9"))
        .isInstanceOf(PaymentFailedException.class);
    assertThat(state.snapshot().payments()).isEmpty();
  }

  @Test
  void validationHappensBeforeCharging() {
    assertThatThrownBy(() -> payments.purchase("u_aarav", "pro_yearly", "tok_ok"))
        .isInstanceOf(ApiException.class)
        .hasMessage("unknown plan: pro_yearly (expected pro_monthly|pro_semester)");
    assertThatThrownBy(() -> payments.purchase("u_nobody", "pro_monthly", "tok_ok"))
        .isInstanceOf(ApiException.class)
        .hasMessage("no such user: u_nobody");
    assertThat(state.snapshot().payments()).isEmpty();
  }

  /* --------------------------------------------------------- status */

  @Test
  void statusIsInactiveBeforePurchaseThenActiveForThePlanWindow() throws Exception {
    PremiumStatus before = payments.status("u_aarav");
    assertThat(before.active()).isFalse();
    assertThat(before.plan()).isNull();
    assertThat(before.payments()).isZero();

    payments.purchase("u_aarav", "pro_monthly", "tok_ok_1111");
    PremiumStatus after = payments.status("u_aarav");

    assertThat(after.active()).isTrue();
    assertThat(after.plan()).isEqualTo("pro_monthly");
    assertThat(after.payments()).isEqualTo(1);
    assertThat(Instant.parse(after.until()))
        .isEqualTo(Instant.parse(after.since()).plus(30, ChronoUnit.DAYS));
  }

  @Test
  void expiryBoundaryIsEvaluatedAgainstTheLedgerRow() {
    // BVA: a monthly row stamped 31 days ago is just outside its window…
    addPayment("u_aarav", "pro_monthly", Instant.now().minus(31, ChronoUnit.DAYS));
    assertThat(payments.status("u_aarav").active()).isFalse();

    // …29 days ago is still inside.
    addPayment("u_riya", "pro_monthly", Instant.now().minus(29, ChronoUnit.DAYS));
    assertThat(payments.status("u_riya").active()).isTrue();

    // …and a semester row 179 days old is still active (180-day window).
    addPayment("u_dev", "pro_semester", Instant.now().minus(179, ChronoUnit.DAYS));
    assertThat(payments.status("u_dev").active()).isTrue();
  }

  @Test
  void latestPaymentDecidesStatus() {
    // An expired monthly row then a fresh one: the newest re-activates.
    addPayment("u_aarav", "pro_monthly", Instant.now().minus(31, ChronoUnit.DAYS));
    assertThat(payments.status("u_aarav").active()).isFalse();
    addPayment("u_aarav", "pro_semester", Instant.now().minus(1, ChronoUnit.DAYS));

    PremiumStatus status = payments.status("u_aarav");
    assertThat(status.active()).isTrue();
    assertThat(status.plan()).isEqualTo("pro_semester");
    assertThat(status.payments()).isEqualTo(2);
  }

  @Test
  void statusOfUnknownUserIsA400() {
    assertThatThrownBy(() -> payments.status("u_nobody"))
        .isInstanceOf(ApiException.class)
        .hasMessage("no such user: u_nobody");
  }

  /* ---------------------------------------------------------- stats */

  @Test
  void statsStartEmptyAndCountCapturedRevenue() throws Exception {
    PremiumStats empty = payments.stats();
    assertThat(empty.payments()).isZero();
    assertThat(empty.revenueMinor()).isZero();
    assertThat(empty.currency()).isEqualTo("INR");
    assertThat(empty.activeSubscribers()).isZero();
    assertThat(empty.byPlan()).allSatisfy(p -> assertThat(p.count()).isZero());

    payments.purchase("u_aarav", "pro_monthly", "tok_ok");
    payments.purchase("u_riya", "pro_monthly", "tok_ok");
    payments.purchase("u_dev", "pro_semester", "tok_ok");

    PremiumStats stats = payments.stats();
    assertThat(stats.payments()).isEqualTo(3);
    assertThat(stats.revenueMinor()).isEqualTo(9900 + 9900 + 34900);
    assertThat(stats.activeSubscribers()).isEqualTo(3);
    assertThat(stats.byPlan()).containsExactly(
        new PremiumStats.PlanStat("pro_monthly", 2, 19800),
        new PremiumStats.PlanStat("pro_semester", 1, 34900));
  }

  /* ----------------------------------------------------------- misc */

  @Test
  void maskingKeepsAtMostFourTrailingDigits() {
    assertThat(PaymentService.mask("tok_ok_4242")).isEqualTo("****4242");
    assertThat(PaymentService.mask("tok_ok")).isEqualTo("****");
    assertThat(PaymentService.mask("tok_12")).isEqualTo("****12");
    assertThat(PaymentService.mask("tok_12345678")).isEqualTo("****5678");
  }

  /** Append a fabricated ledger row at a chosen instant (BVA on expiry). */
  private void addPayment(String userId, String planCode, Instant at) {
    Payment row = new Payment("pay_" + Js.uid(), userId, planCode,
        Plan.byCode(planCode).orElseThrow().amountMinor(), "INR", "****", "captured",
        at.truncatedTo(ChronoUnit.MILLIS).toString());
    state.mutate(db -> {
      List<Payment> rows = new ArrayList<>(db.payments());
      rows.add(row);
      return StateService.bump(db.withPayments(rows));
    });
  }
}
