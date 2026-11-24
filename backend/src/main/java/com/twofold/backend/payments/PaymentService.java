package com.twofold.backend.payments;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.Payment;
import com.twofold.backend.domain.User;
import com.twofold.backend.http.ApiException;
import com.twofold.backend.http.Js;
import com.twofold.backend.state.StateService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The Twofold Pro purchase flow (D4): catalogue lookup → validation →
 * {@link PaymentGateway} charge → one snapshot write.
 *
 * <p>Order matters and is part of the contract:
 *
 * <ol>
 *   <li><b>Validate before charging</b> — an unknown plan or a ghost account
 *       is a 400 and no provider call ever happens (never take money for an
 *       account that does not exist).</li>
 *   <li><b>Charge before writing</b> — a refusal throws
 *       {@link PaymentFailedException} before any state change, so a failed
 *       payment never bumps {@code version} and never lands in the ledger
 *       (the contract's "failed writes don't bump" rule).</li>
 *   <li><b>One write</b> — the captured payment is appended under the same
 *       write lock as every other mutation.</li>
 * </ol>
 *
 * <p>Subscription status is <em>derived</em> from the ledger at read time
 * ({@link #status}), never stored on the user — one source of truth.
 */
@Service
public class PaymentService {

  private final StateService state;
  private final PaymentGateway gateway;

  public PaymentService(StateService state, PaymentGateway gateway) {
    this.state = state;
    this.gateway = gateway;
  }

  /**
   * Buy a plan in sandbox mode and return the fresh snapshot.
   *
   * @throws PaymentFailedException when the sandbox refuses the charge
   * @throws ApiException 400 for unknown plan / unknown user / missing token
   */
  public Database purchase(String userId, String planCode, String cardToken)
      throws PaymentFailedException {
    Plan plan = Plan.byCode(planCode).orElseThrow(() -> ApiException.badRequest(
        "unknown plan: " + planCode + " (expected " + Plan.expected() + ")"));

    Database snapshot = state.snapshot();
    if (findUser(snapshot, userId) == null) {
      throw ApiException.badRequest("no such user: " + userId);
    }

    PaymentGateway.PaymentReceipt receipt = gateway.charge(plan, cardToken);

    Payment payment = new Payment(
        "pay_" + Js.uid(),
        userId,
        plan.code(),
        receipt.amountMinor(),
        plan.currency(),
        mask(cardToken),
        "captured",
        Js.nowIso());

    return state.mutate(db -> {
      List<Payment> payments = new ArrayList<>(db.payments());
      payments.add(payment);
      return StateService.bump(db.withPayments(payments));
    });
  }

  /** One user's subscription standing, derived from the ordered ledger. */
  public PremiumStatus status(String userId) {
    Database db = state.snapshot();
    if (findUser(db, userId) == null) {
      throw ApiException.badRequest("no such user: " + userId);
    }
    int count = 0;
    Payment latest = null;
    for (Payment p : db.payments()) {   // ordered createdAt ASC → last wins
      if (p.userId().equals(userId)) {
        count++;
        latest = p;
      }
    }
    if (latest == null) {
      return new PremiumStatus(userId, false, null, null, null, 0);
    }
    Instant until = expiryOf(latest);
    boolean active = until != null && until.isAfter(Instant.now());
    return new PremiumStatus(userId, active, latest.plan(), latest.createdAt(),
        until == null ? null : until.toString(), count);
  }

  /** Store-wide captured revenue, per plan, plus active subscriber count. */
  public PremiumStats stats() {
    Database db = state.snapshot();
    Instant now = Instant.now();
    Map<String, int[]> byPlan = new LinkedHashMap<>();   // plan → [count, revenue]
    for (Plan plan : Plan.values()) {
      byPlan.put(plan.code(), new int[2]);
    }
    int revenue = 0;
    Set<String> activeUsers = new LinkedHashSet<>();
    for (Payment p : db.payments()) {
      revenue += p.amountMinor();
      int[] acc = byPlan.get(p.plan());
      if (acc != null) {
        acc[0]++;
        acc[1] += p.amountMinor();
      }
      Instant until = expiryOf(p);
      if (until != null && until.isAfter(now)) {
        activeUsers.add(p.userId());
      }
    }
    List<PremiumStats.PlanStat> planStats = byPlan.entrySet().stream()
        .map(e -> new PremiumStats.PlanStat(e.getKey(), e.getValue()[0], e.getValue()[1]))
        .toList();
    return new PremiumStats(db.payments().size(), revenue, "INR", activeUsers.size(), planStats);
  }

  /* ------------------------------------------------------------- helpers */

  /** Expiry of a ledger row; {@code null} if its plan code is unknown. */
  private static Instant expiryOf(Payment p) {
    return Plan.byCode(p.plan())
        .map(plan -> plan.expiresAt(Instant.parse(p.createdAt())))
        .orElse(null);
  }

  /**
   * Sandbox token → {@code ****4242}: the ledger keeps at most the last four
   * digits, never a full token (and never a PAN — this is test mode).
   */
  static String mask(String token) {
    int end = token.length();
    int start = end;
    while (start > 0 && Character.isDigit(token.charAt(start - 1))) {
      start--;
    }
    String tail = token.substring(start);
    if (tail.isEmpty()) {
      return "****";
    }
    return "****" + (tail.length() > 4 ? tail.substring(tail.length() - 4) : tail);
  }

  private static User findUser(Database db, String id) {
    return db.users().stream().filter(u -> u.id().equals(id)).findFirst().orElse(null);
  }
}
