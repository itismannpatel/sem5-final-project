package com.twofold.backend.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Task 3 security gate: {@code POST /api/reset} is **off** unless a deployment
 * opts in. This context deliberately inherits the production default
 * ({@code twofold.reset.enabled=false} in {@code application.properties}) —
 * enabling it in the test properties would test nothing.
 *
 * <p>The rest of the API must stay fully usable with the flag off, which is the
 * real deployment shape: registration, login, the gated snapshot and the public
 * stats endpoint all work while the data-wipe route answers 403.
 */
@SpringBootTest(properties = {
    "twofold.db=target/test-data/reset-gate.db",
    "twofold.store=target/test-data/no-legacy-reset-gate.json",
    "twofold.reminder.outbox=target/test-data/reset-gate-outbox.jsonl",
    "twofold.reminder.initial-delay-ms=3600000",
    "twofold.reminder.delay-ms=3600000"})
@AutoConfigureMockMvc
class ResetGateTest {

  @Autowired
  private MockMvc mvc;

  @Test
  void resetIsForbiddenWhenTheFlagIsOff() throws Exception {
    mvc.perform(post("/api/reset").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("reset is disabled on this deployment"));
  }

  @Test
  void everythingElseStillWorksWithResetDisabled() throws Exception {
    // Unique name and email keep the test idempotent against the shared test DB
    // file (the handle is derived from the name, so it must be unique too).
    long stamp = System.nanoTime();
    String email = "gate." + stamp + "@campus.edu";

    mvc.perform(post("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name":"Gate Tester %d","email":"%s","password":"gate-password-1"}"""
                .formatted(stamp, email)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.user.email").value(email));

    mvc.perform(get("/api/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users").isNumber());

    mvc.perform(get("/api/state"))
        .andExpect(status().isUnauthorized());

    assertThat(email).isNotEmpty();
  }
}
