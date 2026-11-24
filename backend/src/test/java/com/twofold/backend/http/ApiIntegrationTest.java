package com.twofold.backend.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.twofold.backend.auth.AuthRepository;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP-level integration suite for the whole {@code /api/*} contract
 * ({@code docs/testing/API_CONTRACT.md}). It ports the smoke suite's areas into
 * MockMvc — no port is bound — and adds the request/response details the smoke
 * suite never asserted: exact 400/402/404/405 messages, version bump/no-bump per
 * branch, response headers, ordering and the DEF-010 probe set.
 *
 * <p>In-JVM state is a singleton, so every test starts from a fresh reseed:
 * {@code POST /api/reset} in {@code @BeforeEach}. State and outbox files are
 * redirected into {@code target/test-data/}, never the dev store. The scheduled
 * reminder cadence is pushed far into the future so passes are deterministic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "twofold.db=target/test-data/api.db",
    "twofold.store=target/test-data/no-legacy-store.json",
    "twofold.reset.enabled=true",
    "twofold.reminder.outbox=target/test-data/api-outbox.jsonl",
    "twofold.reminder.initial-delay-ms=3600000",
    "twofold.reminder.delay-ms=3600000"})
class ApiIntegrationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();

  /**
   * Task 2: every write needs a session, so the helpers below attach the
   * logged-in cookie by default. Tests that exercise the anonymous path use
   * {@link #anon(MockHttpServletRequestBuilder)} instead.
   */
  private static final AtomicReference<Cookie> SESSION = new AtomicReference<>();

  /**
   * Task 3 removed the shared demo password; seeded profiles have no accounts.
   * The suite attaches its own test-only hash directly (the fixture option the
   * brief allows) so the seeded rows stay usable by the existing cases. One
   * hash, computed once, keeps the per-test setup cheap.
   */
  private static final String TEST_PASSWORD = "integration-fixture-password";
  private static final String TEST_HASH =
      new BCryptPasswordEncoder().encode(TEST_PASSWORD);
  private static final List<String> FIXTURE_USERS = List.of(
      "u_aarav", "u_riya", "u_kabir", "u_meera", "u_rohan", "u_dev");

  @Autowired
  private MockMvc mvc;

  @Autowired
  private AuthRepository authRepository;

  /** The default: send the session cookie minted in {@link #resetSeed()}. */
  private ResultActions perform(MockHttpServletRequestBuilder builder) throws Exception {
    Cookie cookie = SESSION.get();
    return mvc.perform(cookie == null ? builder : builder.cookie(cookie));
  }

  /** A deliberately logged-out request (no cookie). */
  private ResultActions anon(MockHttpServletRequestBuilder builder) throws Exception {
    return mvc.perform(builder);
  }

  @BeforeEach
  void resetSeed() throws Exception {
    perform(post("/api/reset")).andExpect(status().isOk());

    // Reset clears credentials and sessions (Task 3), so the fixture accounts
    // are attached after every reseed.
    for (String userId : FIXTURE_USERS) {
      authRepository.saveAccount(userId, TEST_HASH);
    }

    Cookie fresh = mvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"aarav.mehta@campus.edu","password":"integration-fixture-password"}"""))
        .andExpect(status().isOk())
        .andReturn().getResponse().getCookie("twofold_session");
    assertThat(fresh).isNotNull();
    SESSION.set(fresh);
  }

  /**
   * Switch the session to another fixture student. Task 2 makes the actor
   * authoritative, so a test that writes as someone else has to actually be
   * that someone; {@link #resetSeed()} attached the fixture credential.
   */
  private void signInAs(String email) throws Exception {
    Cookie fresh = mvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\""
                + TEST_PASSWORD + "\"}"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getCookie("twofold_session");
    assertThat(fresh).isNotNull();
    SESSION.set(fresh);
  }

  /* ------------------------------------- Task 3: public stats + gated reads */

  @Test
  void stateAndEveryUserScopedReadRequireASession() throws Exception {
    int before = version();

    anon(get("/api/state"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("authentication required"));
    anon(get("/api/matches").param("userId", "u_aarav"))
        .andExpect(status().isUnauthorized());
    anon(get("/api/premium"))
        .andExpect(status().isUnauthorized());
    anon(get("/api/reminders"))
        .andExpect(status().isUnauthorized());

    // With the cookie the same reads answer as before, and reads never write.
    perform(get("/api/state")).andExpect(status().isOk())
        .andExpect(jsonPath("$.users", hasSize(12)));
    assertThat(version()).isEqualTo(before);
  }

  @Test
  void publicStatsAreAnonymousSanitizedAndStable() throws Exception {
    String body = anon(get("/api/stats"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.users").value(12))
        .andExpect(jsonPath("$.skills").value(41))
        .andExpect(jsonPath("$.completed").value(9))
        .andExpect(jsonPath("$.featured", hasSize(3)))
        .andExpect(jsonPath("$.featured[0].id").isString())
        .andExpect(jsonPath("$.featured[0].handle").isString())
        .andExpect(jsonPath("$.featured[0].teaches").isArray())
        .andExpect(jsonPath("$.featured[0].rating.average").isNumber())
        .andExpect(jsonPath("$.heroScore").isNumber())
        .andExpect(jsonPath("$.quote.text").isString())
        .andReturn().getResponse().getContentAsString();

    // The landing payload must not carry contact details or credential hints.
    assertThat(body).doesNotContain("@campus");
    assertThat(body).doesNotContain("password");
    assertThat(body).doesNotContain("joinedAt");
  }

  @Test
  void seededStudentsHaveNoCredentialsAfterReset() throws Exception {
    // Task 3: the seed is content, not an account. A clean reset leaves the 12
    // profiles but zero ways to log into them.
    perform(post("/api/reset")).andExpect(status().isOk());

    anon(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"aarav.mehta@campus.edu","password":"integration-fixture-password"}"""))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("invalid email or password"));
  }

  /* --------------------------------------------------------- helpers */

  private ResultActions postJson(String path, String json) throws Exception {
    return perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private ResultActions patchJson(String path, String json) throws Exception {
    return perform(patch(path).contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private int version() throws Exception {
    String body = perform(get("/api/state")).andReturn().getResponse().getContentAsString();
    @SuppressWarnings("unchecked")
    Map<String, Object> doc = MAPPER.readValue(body, Map.class);
    return ((Number) doc.get("version")).intValue();
  }

  private Map<String, Object> asMap(ResultActions result) throws Exception {
    String body = result.andReturn().getResponse().getContentAsString();
    @SuppressWarnings("unchecked")
    Map<String, Object> doc = MAPPER.readValue(body, Map.class);
    return doc;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> doc, String key) {
    return (List<Map<String, Object>>) doc.get(key);
  }

  /** The single row where {@code field} equals {@code value} (JsonPath filters are avoided here). */
  private static Map<String, Object> row(List<Map<String, Object>> rows, String field, String value) {
    return rows.stream().filter(r -> value.equals(r.get(field))).findFirst().orElseThrow();
  }

  /* --------------------------------------------- TC-001 snapshot baseline */

  @Test
  void baselineSnapshotHasSeedCountsContractHeadersAndHydratedRows() throws Exception {
    perform(get("/api/state"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Content-Type", containsString("application/json")))
        .andExpect(jsonPath("$.version").isNumber())
        .andExpect(jsonPath("$.users", hasSize(12)))
        .andExpect(jsonPath("$.skills", hasSize(41)))
        .andExpect(jsonPath("$.sessions", hasSize(12)))
        .andExpect(jsonPath("$.reviews", hasSize(14)))
        .andExpect(jsonPath("$.messages", hasSize(9)))
        .andExpect(jsonPath("$.payments", hasSize(0)))
        .andExpect(jsonPath("$.users[0].teaches").isArray())
        .andExpect(jsonPath("$.users[0].wants").isArray())
        .andExpect(jsonPath("$.messages[0].readBy").isArray());
  }

  /* ------------------------------------ TC-003 / TC-043 reset restores seed */

  @Test
  void resetRestoresTheSeedAndClearsThePaymentLedger() throws Exception {
    postJson("/api/premium", """
        {"userId":"u_aarav","plan":"pro_monthly","cardToken":"tok_ok"}""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.payments", hasSize(1)));
    int before = version();

    postJson("/api/reset", "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(before + 1))
        .andExpect(jsonPath("$.users", hasSize(12)))
        .andExpect(jsonPath("$.skills", hasSize(41)))
        .andExpect(jsonPath("$.sessions", hasSize(12)))
        .andExpect(jsonPath("$.reviews", hasSize(14)))
        .andExpect(jsonPath("$.messages", hasSize(9)))
        .andExpect(jsonPath("$.payments", hasSize(0)));
  }

  /* ------------------------------------------- users: create (TC-066) */

  @Test
  void createdUsersGetDocumentedDefaultsAndADerivedHandle() throws Exception {
    int before = version();

    ResultActions created = postJson("/api/users", """
        {"name":"Zed Test","email":"zed.test@campus.edu"}""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(before + 1))
        .andExpect(jsonPath("$.users", hasSize(13)));

    Map<String, Object> user = row(rows(asMap(created), "users"), "email",
        "zed.test@campus.edu");
    assertThat(user.get("handle")).isEqualTo("zed.test");
    assertThat(user.get("branch")).isEqualTo("Undeclared");
    assertThat(user.get("year")).isEqualTo("1st year");
    assertThat(user.get("format")).isEqualTo("Either");
    assertThat((List<?>) user.get("teaches")).isEmpty();
    int hue = ((Number) user.get("hue")).intValue();
    assertThat(hue).isBetween(0, 359);
  }

  @Test
  void duplicateEmailAndHandleAreExact400sWithoutABump() throws Exception {
    int before = version();

    postJson("/api/users", """
        {"name":"Someone","email":"aarav.mehta@campus.edu"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error")
            .value("email is already registered: aarav.mehta@campus.edu"));

    // Case-insensitive email uniqueness (DEF-010: Node silently duplicated).
    postJson("/api/users", """
        {"name":"Someone","email":"AARAV.MEHTA@campus.edu"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(containsString("email is already registered")));

    // Same name → same derived handle → rejected, not a 500.
    postJson("/api/users", """
        {"name":"Aarav","email":"other@campus.edu"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("handle is already taken: aarav"));

    assertThat(version()).isEqualTo(before);
  }

  /* ------------------------------------------- users: PATCH (TC-018…024) */

  @Test
  void patchAcceptsTheThreeDocumentedFormatsAndRejectsEverythingElse() throws Exception {
    int before = version();

    for (String format : List.of("Online", "On campus", "Either")) {
      patchJson("/api/users/u_aarav", """
          {"format":"%s"}""".formatted(format))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.version").isNumber());
    }
    assertThat(version()).isEqualTo(before + 3);

    int settled = version();
    for (String bad : List.of("Offline", "online", "EITHER", "")) {
      patchJson("/api/users/u_aarav", """
          {"format":"%s"}""".formatted(bad))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.error").value("format must be Online, On campus or Either"));
    }
    patchJson("/api/users/u_aarav", """
        {"format":null}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("format must be Online, On campus or Either"));

    assertThat(version()).isEqualTo(settled);
  }

  @Test
  void patchReplacesSkillsWholesaleAndValidatesLevelAndForeignKeys() throws Exception {
    int before = version();

    ResultActions patched = patchJson("/api/users/u_aarav", """
        {"teaches":[{"skillId":"sk_js","level":"Beginner"},
                    {"skillId":"sk_figma","level":"Intermediate"},
                    {"skillId":"sk_rust","level":"Advanced"}]}""")
        .andExpect(status().isOk());
    List<Map<String, Object>> teaches =
        (List<Map<String, Object>>) row(rows(asMap(patched), "users"), "id", "u_aarav")
            .get("teaches");
    assertThat(teaches).extracting(t -> t.get("skillId"))
        .containsExactly("sk_js", "sk_figma", "sk_rust");
    assertThat(version()).isEqualTo(before + 1);

    int settled = version();
    patchJson("/api/users/u_aarav", """
        {"teaches":[{"skillId":"sk_js","level":"Expert"}]}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("level must be Beginner, Intermediate or Advanced"));
    patchJson("/api/users/u_aarav", """
        {"teaches":[{"skillId":"sk_js","level":"beginner"}]}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("level must be Beginner, Intermediate or Advanced"));
    patchJson("/api/users/u_aarav", """
        {"wants":[{"skillId":"sk_nonexistent","level":"Beginner"}]}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("no such skill: sk_nonexistent"));

    assertThat(version()).isEqualTo(settled);
  }

  @Test
  void patchOfAnUnknownOrOtherUserIsForbiddenAndBumpFree() throws Exception {
    // Task 2 replaced the old "0-row UPDATE still bumps" quirk: an id that is
    // not the actor is a 403 (and that includes ids that do not exist at all,
    // which also avoids confirming whether they exist).
    int before = version();

    patchJson("/api/users/u_nonexistent", """
        {"bio":"x"}""")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only edit your own profile"));

    patchJson("/api/users/u_nonexistent", """
        {"teaches":[{"skillId":"sk_js","level":"Beginner"}]}""")
        .andExpect(status().isForbidden());

    patchJson("/api/users/u_riya", """
        {"bio":"not mine"}""")
        .andExpect(status().isForbidden());

    assertThat(version()).isEqualTo(before);
  }

  /* --------------------------------- sessions: create validation (TC-017) */

  @Test
  void sessionCreateRejectsEveryInvalidShapeWithTheExactMessage() throws Exception {
    int before = version();

    postJson("/api/sessions", """
        {"teacherId":"u_riya","learnerId":"u_aarav","skillId":"sk_figma"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error")
            .value("teacherId, learnerId, skillId and topic are required"));

    postJson("/api/sessions", """
        {"teacherId":"u_riya","learnerId":"u_aarav","skillId":"sk_figma",
         "topic":"T","proposedSlots":[]}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("at least one proposed slot is required"));

    postJson("/api/sessions", """
        {"teacherId":"u_riya","learnerId":"u_aarav","skillId":"sk_figma",
         "topic":"T","proposedSlots":"2026-12-01T11:00:00.000Z"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("at least one proposed slot is required"));

    postJson("/api/sessions", """
        {"teacherId":"u_riya","learnerId":"u_aarav","skillId":"sk_figma",
         "topic":"T","proposedSlots":[123]}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("at least one proposed slot is required"));

    postJson("/api/sessions", """
        {"teacherId":"u_nonexistent","learnerId":"u_aarav","skillId":"sk_figma",
         "topic":"T","proposedSlots":["2026-12-01T11:00:00.000Z"]}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("no such teacher: u_nonexistent"));

    // Naming another learner is acting as them — a 403, not a validation 400.
    postJson("/api/sessions", """
        {"teacherId":"u_riya","learnerId":"u_nonexistent","skillId":"sk_figma",
         "topic":"T","proposedSlots":["2026-12-01T11:00:00.000Z"]}""")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only request sessions as yourself"));

    postJson("/api/sessions", """
        {"teacherId":"u_aarav","learnerId":"u_aarav","skillId":"sk_figma",
         "topic":"T","proposedSlots":["2026-12-01T11:00:00.000Z"]}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("teacherId and learnerId must be different"));

    postJson("/api/sessions", """
        {"teacherId":"u_riya","learnerId":"u_aarav","skillId":"sk_nonexistent",
         "topic":"T","proposedSlots":["2026-12-01T11:00:00.000Z"]}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("no such skill: sk_nonexistent"));

    assertThat(version()).isEqualTo(before);
  }

  /* ------------------------------- sessions: lifecycle + state machine (DT) */

  @Test
  void sessionLifecycleFollowsTheDecisionTable() throws Exception {
    int before = version();

    ResultActions createdResult = postJson("/api/sessions", """
        {"teacherId":"u_riya","learnerId":"u_aarav","skillId":"sk_figma",
         "topic":"Integration test session","note":"bring a laptop",
         "proposedSlots":["2026-12-01T11:00:00.000Z","2026-12-03T11:00:00.000Z"]}""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(before + 1));
    Map<String, Object> createdSession = row(rows(asMap(createdResult), "sessions"), "topic",
        "Integration test session");
    assertThat(createdSession.get("status")).isEqualTo("proposed");
    assertThat((List<?>) createdSession.get("proposedSlots")).hasSize(2);
    assertThat(createdSession).doesNotContainKey("scheduledFor");
    String id = (String) createdSession.get("id");

    // accept without a slot → 400, unchanged
    int settled = version();
    postJson("/api/sessions/" + id + "/accept", "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("slot is required"));
    assertThat(version()).isEqualTo(settled);

    ResultActions accepted = postJson("/api/sessions/" + id + "/accept", """
        {"slot":"2026-12-03T11:00:00.000Z"}""")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(settled + 1));
    Map<String, Object> acceptedSession = row(rows(asMap(accepted), "sessions"), "id", id);
    assertThat(acceptedSession.get("status")).isEqualTo("confirmed");
    assertThat(acceptedSession.get("scheduledFor")).isEqualTo("2026-12-03T11:00:00.000Z");

    ResultActions completed = postJson("/api/sessions/" + id + "/complete", "{}")
        .andExpect(status().isOk());
    Map<String, Object> completedSession = row(rows(asMap(completed), "sessions"), "id", id);
    assertThat(completedSession.get("status")).isEqualTo("completed");
    assertThat((String) completedSession.get("completedAt"))
        .matches("\\d{4}-\\d{2}-\\d{2}T.*Z");

    int beforeUnknownAction = version();
    postJson("/api/sessions/" + id + "/reschedule", "{}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Unknown session action: reschedule"));
    assertThat(version()).isEqualTo(beforeUnknownAction);
  }

  @Test
  void declineCancelAndTerminalReActionAllWriteAsDocumented() throws Exception {
    ResultActions declined = postJson("/api/sessions/s_react_kabir/decline", "{}")
        .andExpect(status().isOk());
    assertThat(row(rows(asMap(declined), "sessions"), "id", "s_react_kabir")
        .get("status")).isEqualTo("declined");

    // DT D5: no status guard — a terminal session can be re-actioned.
    ResultActions reAccepted = postJson("/api/sessions/s_react_kabir/accept", """
        {"slot":"2026-12-05T09:00:00.000Z"}""")
        .andExpect(status().isOk());
    assertThat(row(rows(asMap(reAccepted), "sessions"), "id", "s_react_kabir")
        .get("status")).isEqualTo("confirmed");

    ResultActions cancelled = postJson("/api/sessions/s_figma/cancel", "{}")
        .andExpect(status().isOk());
    assertThat(row(rows(asMap(cancelled), "sessions"), "id", "s_figma")
        .get("status")).isEqualTo("cancelled");
  }

  @Test
  void unknownSessionIdStillBumpsTheVersion() throws Exception {
    int before = version();

    postJson("/api/sessions/s_nonexistent/decline", "{}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(before + 1))
        .andExpect(jsonPath("$.sessions", hasSize(12)));
  }

  /* ------------------------------------- reviews: rating EP/BVA (TC-004…011) */

  @Test
  void reviewRatingBoundariesAndCoercions() throws Exception {
    // The author is the signed-in actor now, so be Rohan for this test.
    signInAs("rohan.das@campus.edu");
    int before = version();

    ResultActions created = postJson("/api/reviews", """
        {"sessionId":"s_react_kabir","fromUserId":"u_rohan","toUserId":"u_aarav",
         "rating":4.5}""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(before + 1));
    // 4.5 passes the pre-rounding range check, then Math.round stores 5.
    assertThat(row(rows(asMap(created), "reviews"), "fromUserId", "u_rohan")
        .get("rating")).isEqualTo(5);

    int settled = version();
    for (String bad : List.of("0", "6", "9", "\"excellent\"", "null")) {
      postJson("/api/reviews", """
          {"sessionId":"s_speaking","fromUserId":"u_dev","toUserId":"u_meera",
           "rating":%s}""".formatted(bad))
          .andExpect(status().isBadRequest());
    }
    postJson("/api/reviews", """
        {"fromUserId":"u_dev","toUserId":"u_meera","rating":3}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error")
            .value("sessionId, fromUserId, toUserId and rating are required"));
    postJson("/api/reviews", """
        {"sessionId":"s_speaking","fromUserId":"u_dev","toUserId":"u_meera",
         "rating":6}""")
        .andExpect(jsonPath("$.error").value("rating must be between 1 and 5"));
    postJson("/api/reviews", """
        {"sessionId":"s_speaking","fromUserId":"u_dev","toUserId":"u_meera",
         "rating":0}""")
        .andExpect(jsonPath("$.error")
            .value("sessionId, fromUserId, toUserId and rating are required"));

    assertThat(version()).isEqualTo(settled);
  }

  @Test
  void duplicateReviewIsANoOpThatStillAnswers201() throws Exception {
    signInAs("dev.patel@campus.edu");
    String body = """
        {"sessionId":"s_speaking","fromUserId":"u_dev","toUserId":"u_meera",
         "rating":5,"text":"great"}""";
    postJson("/api/reviews", body).andExpect(status().isCreated());
    int before = version();

    postJson("/api/reviews", """
        {"sessionId":"s_speaking","fromUserId":"u_dev","toUserId":"u_meera",
         "rating":1,"text":"changed"}""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(before))
        .andExpect(jsonPath("$.reviews", hasSize(15)));
  }

  @Test
  void reviewUnknownReferencesAre400sWithNoBump() throws Exception {
    signInAs("dev.patel@campus.edu");
    int before = version();

    postJson("/api/reviews", """
        {"sessionId":"s_nope","fromUserId":"u_dev","toUserId":"u_meera","rating":3}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("no such session: s_nope"));
    // Writing as someone else is forbidden before any row lookup happens.
    postJson("/api/reviews", """
        {"sessionId":"s_speaking","fromUserId":"u_nope","toUserId":"u_meera","rating":3}""")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only review as yourself"));
    postJson("/api/reviews", """
        {"sessionId":"s_speaking","fromUserId":"u_dev","toUserId":"u_nope","rating":3}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("no such user: u_nope"));

    assertThat(version()).isEqualTo(before);
  }

  /* ----------------------------------------------- messages (TC-061/TC-067) */

  @Test
  void sendingAMessageAutoReadsItAndMarkReadIsIdempotent() throws Exception {
    int before = version();

    ResultActions sent = postJson("/api/messages", """
        {"fromUserId":"u_aarav","toUserId":"u_riya","body":"Integration hello"}""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(before + 1))
        .andExpect(jsonPath("$.messages", hasSize(10)));
    Map<String, Object> message = row(rows(asMap(sent), "messages"), "body",
        "Integration hello");
    @SuppressWarnings("unchecked")
    List<Object> readBy = (List<Object>) message.get("readBy");
    assertThat(readBy).containsExactly("u_aarav");

    int afterSend = version();
    String readBody = """
        {"userId":"u_aarav","partnerId":"u_kabir"}""";
    postJson("/api/messages/read", readBody)
        .andExpect(status().isOk());
    int afterFirstRead = version();
    assertThat(afterFirstRead).isEqualTo(afterSend + 1);

    postJson("/api/messages/read", readBody)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(afterFirstRead));
  }

  @Test
  void messageValidationIsExactAndBumpFree() throws Exception {
    int before = version();

    postJson("/api/messages", """
        {"fromUserId":"u_aarav","toUserId":"u_riya","body":"   "}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("fromUserId, toUserId and body are required"));
    postJson("/api/messages", """
        {"fromUserId":"u_aarav","toUserId":"u_nope","body":"hi"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("no such user: u_nope"));
    postJson("/api/messages/read", """
        {"userId":"u_aarav"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("userId and partnerId are required"));

    assertThat(version()).isEqualTo(before);
  }

  /* ------------------------------------------- matching endpoint (TC-050/051) */

  @Test
  void matchesAreRankedDescendingAndAllModeIncludesEveryone() throws Exception {
    perform(get("/api/matches").param("userId", "u_aarav"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].score").isNumber())
        .andExpect(jsonPath("$[0].breakdown.learn").exists())
        .andExpect(jsonPath("$[0].user.id").exists());

    String body = perform(get("/api/matches").param("userId", "u_aarav"))
        .andReturn().getResponse().getContentAsString();
    List<?> ranked = (List<?>) MAPPER.readValue(body, List.class);
    for (int i = 1; i < ranked.size(); i++) {
      int prev = ((Number) ((Map<?, ?>) ranked.get(i - 1)).get("score")).intValue();
      int cur = ((Number) ((Map<?, ?>) ranked.get(i)).get("score")).intValue();
      assertThat(cur).isLessThanOrEqualTo(prev);
    }

    perform(get("/api/matches").param("userId", "u_aarav").param("mode", "all"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(11)));
  }

  @Test
  void matchesReturnTheContractErrorsAndCachingNeverServesStaleData() throws Exception {
    int before = version();
    perform(get("/api/matches"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("userId is required"));
    perform(get("/api/matches").param("userId", "u_nope"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("no such user: u_nope"));
    assertThat(version()).isEqualTo(before);

    String first = perform(get("/api/matches").param("userId", "u_aarav"))
        .andReturn().getResponse().getContentAsString();
    String second = perform(get("/api/matches").param("userId", "u_aarav"))
        .andReturn().getResponse().getContentAsString();
    assertThat(second).isEqualTo(first);   // same version → cache hit

    // A write bumps the version; the next read must recompute, not replay.
    patchJson("/api/users/u_aarav", """
        {"wants":[]}""")
        .andExpect(status().isOk());
    String third = perform(get("/api/matches").param("userId", "u_aarav"))
        .andReturn().getResponse().getContentAsString();
    assertThat(third).isNotEqualTo(first);
  }

  /* --------------------------------------------- premium endpoint (TC-033…039) */

  @Test
  void premiumPurchaseIsCreatedAndVisibleInStatsAndStatus() throws Exception {
    int before = version();

    postJson("/api/premium", """
        {"userId":"u_aarav","plan":"pro_monthly","cardToken":"tok_ok_4242"}""")
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(before + 1))
        .andExpect(jsonPath("$.payments", hasSize(1)))
        .andExpect(jsonPath("$.payments[0].amountMinor").value(9900))
        .andExpect(jsonPath("$.payments[0].currency").value("INR"))
        .andExpect(jsonPath("$.payments[0].cardToken").value("****4242"));

    perform(get("/api/premium"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payments").value(1))
        .andExpect(jsonPath("$.revenueMinor").value(9900))
        .andExpect(jsonPath("$.activeSubscribers").value(1))
        .andExpect(jsonPath("$.byPlan[0].count").value(1));

    perform(get("/api/premium").param("userId", "u_aarav"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.plan").value("pro_monthly"))
        .andExpect(jsonPath("$.payments").value(1))
        .andExpect(jsonPath("$.until").exists());
  }

  @Test
  void declinedPaymentIs402WithNoStateChange() throws Exception {
    int before = version();

    postJson("/api/premium", """
        {"userId":"u_aarav","plan":"pro_monthly","cardToken":"tok_decline_1"}""")
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.error").value(containsString("payment failed")))
        .andExpect(jsonPath("$.error").value(containsString("declined")));

    postJson("/api/premium", """
        {"userId":"u_aarav","plan":"pro_monthly","cardToken":"tok_insufficient_1"}""")
        .andExpect(status().isPaymentRequired())
        .andExpect(jsonPath("$.error").value(containsString("insufficient funds")));

    assertThat(version()).isEqualTo(before);
    perform(get("/api/state")).andExpect(jsonPath("$.payments", hasSize(0)));
  }

  @Test
  void premiumValidationMessagesAreExactAndBumpFree() throws Exception {
    int before = version();

    postJson("/api/premium", """
        {"plan":"pro_monthly","cardToken":"tok_ok"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("userId is required"));
    postJson("/api/premium", """
        {"userId":"u_aarav","cardToken":"tok_ok"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("plan is required"));
    postJson("/api/premium", """
        {"userId":"u_aarav","plan":"pro_monthly"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("cardToken is required"));
    postJson("/api/premium", """
        {"userId":"u_aarav","plan":"pro_yearly","cardToken":"tok_ok"}""")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error")
            .value("unknown plan: pro_yearly (expected pro_monthly|pro_semester)"));
    postJson("/api/premium", """
        {"userId":"u_nope","plan":"pro_monthly","cardToken":"tok_ok"}""")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only buy Pro for yourself"));

    assertThat(version()).isEqualTo(before);
  }

  /* ------------------------------------------- reminders endpoint (TC-055/057) */

  @Test
  void reminderStatsAreReadOnlyAndExposeTheDocumentedShape() throws Exception {
    int before = version();

    perform(get("/api/reminders"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.runs").isNumber())
        .andExpect(jsonPath("$.due").isNumber())
        .andExpect(jsonPath("$.written").isNumber())
        .andExpect(jsonPath("$.windowHours").value(168))
        .andExpect(jsonPath("$.outbox").isString())
        .andExpect(jsonPath("$.recent").isArray());

    assertThat(version()).isEqualTo(before);
  }

  /* ------------------------------------- protocol matrix (TC-068…070) */

  @Test
  void routingMatrixAnswersTheExact405And404Strings() throws Exception {
    int before = version();

    perform(get("/api/users"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error").value("Method not allowed"));
    perform(put("/api/state").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isMethodNotAllowed())
        .andExpect(jsonPath("$.error").value("Method not allowed"));
    perform(post("/api/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("No such endpoint: POST /api/nope"));
    perform(patch("/api/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("No such endpoint: PATCH /api/nope"));

    perform(get("/api/state")).andExpect(status().isOk());
    assertThat(version()).isEqualTo(before);
  }

  @Test
  void malformedJsonBehavesLikeAnEmptyBodyAndNever500s() throws Exception {
    int before = version();

    perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Broken\","))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("name and email are required"));
    perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("[1,2]"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("name and email are required"));
    perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("name and email are required"));

    assertThat(version()).isEqualTo(before);
  }

  @Test
  void errorResponsesCarryTheContractHeadersToo() throws Exception {
    perform(get("/api/matches"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Content-Type", containsString("application/json")))
        .andExpect(content().string(containsString("\"error\"")));

    perform(post("/api/nope").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("Content-Type", containsString("application/json")));
  }

  /* ------------------------------------------------- auth (Task 2, TC-072…) */

  @Test
  void writesWithoutASessionAreRejectedWith401AndChangeNothing() throws Exception {
    int before = version();

    anon(post("/api/messages")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"fromUserId":"u_aarav","toUserId":"u_riya","body":"anonymous"}"""))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.error").value("authentication required"));

    anon(post("/api/sessions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"teacherId":"u_riya","learnerId":"u_aarav","skillId":"sk_figma",
                 "topic":"anon","proposedSlots":["2026-12-01T10:00:00.000Z"]}"""))
        .andExpect(status().isUnauthorized());

    anon(patch("/api/users/u_aarav")
            .contentType(MediaType.APPLICATION_JSON).content("{\"bio\":\"anon\"}"))
        .andExpect(status().isUnauthorized());

    assertThat(version()).isEqualTo(before);
  }

  @Test
  void loginAcceptsEmailOrHandleAndIssuesAnHttpOnlySameSiteCookie() throws Exception {
    perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"aarav.mehta@campus.edu","password":"not-the-password"}"""))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("invalid email or password"));

    perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"handle":"aarav","password":"integration-fixture-password"}"""))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", containsString("twofold_session=")))
        .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
        .andExpect(header().string("Set-Cookie", containsString("SameSite=Lax")))
        .andExpect(header().string("Set-Cookie", containsString("Path=/")))
        .andExpect(jsonPath("$.user.id").value("u_aarav"))
        .andExpect(jsonPath("$.database.users", hasSize(12)));
  }

  @Test
  void meResolvesTheCookieAndRejectsAnonymous() throws Exception {
    perform(get("/api/auth/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.id").value("u_aarav"))
        .andExpect(jsonPath("$.user.email").value("aarav.mehta@campus.edu"))
        .andExpect(jsonPath("$.user.passwordHash").doesNotExist());

    anon(get("/api/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("not signed in"));
  }

  @Test
  void actingForAnotherUserIsForbidden() throws Exception {
    int before = version();

    postJson("/api/messages", """
        {"fromUserId":"u_riya","toUserId":"u_aarav","body":"impersonation"}""")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only send messages as yourself"));

    postJson("/api/reviews", """
        {"sessionId":"s_seed_completed_1","fromUserId":"u_riya","toUserId":"u_aarav",
         "rating":5}""")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only review as yourself"));

    postJson("/api/premium", """
        {"userId":"u_riya","plan":"pro_monthly","cardToken":"tok_visa_4242"}""")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only buy Pro for yourself"));

    patchJson("/api/users/u_riya", "{\"bio\":\"hijacked\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only edit your own profile"));

    postJson("/api/sessions", """
        {"teacherId":"u_riya","learnerId":"u_kabir","skillId":"sk_figma",
         "topic":"not mine","proposedSlots":["2026-12-01T10:00:00.000Z"]}""")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you can only request sessions as yourself"));

    assertThat(version()).isEqualTo(before);
    Map<String, Object> doc = asMap(perform(get("/api/state")));
    assertThat(row(rows(doc, "users"), "id", "u_riya").get("bio"))
        .isNotEqualTo("hijacked");
  }

  @Test
  void nonParticipantsCannotMoveSomeoneElsesSession() throws Exception {
    // Aarav has no part in this seeded session (teacher riya → learner meera).
    Map<String, Object> doc = asMap(perform(get("/api/state")));
    Map<String, Object> foreign = rows(doc, "sessions").stream()
        .filter(s -> !"u_aarav".equals(s.get("teacherId"))
            && !"u_aarav".equals(s.get("learnerId")))
        .findFirst().orElseThrow();

    postJson("/api/sessions/" + foreign.get("id") + "/complete", "{}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("you are not a participant in this session"));
  }

  @Test
  void logoutInvalidatesTheTokenServerSide() throws Exception {
    perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"aarav.mehta@campus.edu","password":"integration-fixture-password"}"""))
        .andExpect(status().isOk());

    Cookie token = SESSION.get();
    anon(post("/api/auth/logout").cookie(token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ok").value(true));

    anon(post("/api/messages").cookie(token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"fromUserId":"u_aarav","toUserId":"u_riya","body":"after logout"}"""))
        .andExpect(status().isUnauthorized());
    anon(get("/api/auth/me").cookie(token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void registrationCreatesACredentialedAccountAndSignsIn() throws Exception {
    String newEmail = "new.student@campus.edu";

    perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"New Student","email":"new.student@campus.edu",
                 "password":"correct-horse","branch":"Computer Science"}"""))
        .andExpect(status().isCreated())
        .andExpect(header().string("Set-Cookie", containsString("twofold_session=")))
        .andExpect(jsonPath("$.user.handle").value("new.student"))
        .andExpect(jsonPath("$.database.users", hasSize(13)));

    perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"new.student@campus.edu","password":"correct-horse"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.email").value(newEmail));

    perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"new.student@campus.edu","password":"wrong"}"""))
        .andExpect(status().isUnauthorized());

    // Short passwords are a validation failure, not an auth failure.
    perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Short","email":"short@campus.edu","password":"short"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("password must be at least 8 characters"));
  }
}
