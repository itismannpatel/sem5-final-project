package com.twofold.backend.http;

import com.twofold.backend.auth.AuthService;
import com.twofold.backend.auth.AuthSession;
import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.ExchangeSkill;
import com.twofold.backend.domain.Message;
import com.twofold.backend.domain.Review;
import com.twofold.backend.domain.Session;
import com.twofold.backend.domain.User;
import com.twofold.backend.jobs.ReminderJob;
import com.twofold.backend.matching.MatchEngine;
import com.twofold.backend.matching.MatchService;
import com.twofold.backend.payments.PaymentFailedException;
import com.twofold.backend.payments.PaymentService;
import com.twofold.backend.state.StateService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole {@code /api} surface — a 1:1 port of {@code server/api.ts},
 * including its pinned routing order, its exact validation strings, and its
 * quirk that a failed write never bumps the version.
 *
 * <p>Where Node let a client mistake fall into a schema violation (a CHECK or
 * FOREIGN KEY) and answered a bare 500, this answers 400 with a specific
 * message instead — that is DEF-010 in {@code docs/testing/DEFECT_LOG.md}.
 * Everything else mirrors the reference implementation exactly;
 * {@code scripts/api-smoke.mjs} is the executable proof.
 *
 * <p>Every mutating branch returns the fresh snapshot, so a write still costs
 * exactly one round trip: POST in, new state out, re-render.
 */
@RestController
public class ApiController {

  private static final MediaType JSON_CT =
      MediaType.parseMediaType("application/json; charset=utf-8");

  private static final Set<String> FORMATS = Set.of("Online", "On campus", "Either");
  private static final Set<String> LEVELS = Set.of("Beginner", "Intermediate", "Advanced");

  private final StateService state;
  private final ObjectMapper mapper;
  private final MatchService matchService;
  private final PaymentService paymentService;
  private final ReminderJob reminderJob;
  private final AuthService auth;
  /** Fail closed: the data-wipe endpoint is off unless a deployment opts in. */
  private final boolean resetEnabled;

  public ApiController(StateService state, ObjectMapper mapper, MatchService matchService,
      PaymentService paymentService, ReminderJob reminderJob, AuthService auth,
      @Value("${twofold.reset.enabled:false}") boolean resetEnabled) {
    this.state = state;
    this.mapper = mapper;
    this.matchService = matchService;
    this.paymentService = paymentService;
    this.reminderJob = reminderJob;
    this.auth = auth;
    this.resetEnabled = resetEnabled;
  }

  @RequestMapping("/api/**")
  public ResponseEntity<?> dispatch(HttpServletRequest request)
      throws IOException, PaymentFailedException {
    String path = request.getRequestURI();
    String context = request.getContextPath();
    if (!context.isEmpty() && path.startsWith(context)) {
      path = path.substring(context.length());
    }
    String method = request.getMethod();
    List<String> parts = split(path);

    // -------------------------------------------------------------- auth (T2)
    // Register / login / logout / me. These are the only write routes that do
    // not need an actor — they are how an actor is obtained.
    if (path.startsWith("/api/auth/")) {
      return authRoute(request, method, path);
    }

    // ------------------------------------------------------------- reads
    String actor = auth.userIdFor(sessionToken(request));

    // Public by design (Task 3): the landing page renders for signed-out
    // visitors from aggregates and a few sanitized cards — never user rows and
    // never an email address. See publicStats().
    if (method.equals("GET") && path.equals("/api/stats")) {
      return respond(publicStats(), 200);
    }

    // Every other read exposes user rows (state), per-user ranking (matches),
    // the ledger (premium) or job telemetry (reminders), so it needs a session.
    if (method.equals("GET") && (path.equals("/api/state")
        || path.equals("/api/matches") || path.equals("/api/premium")
        || path.equals("/api/reminders")) && actor == null) {
      throw ApiException.unauthorized("authentication required");
    }

    if (method.equals("GET") && path.equals("/api/state")) {
      return respond(state.snapshot(), 200);
    }

    // D3: the matching engine lives server-side. mode=all scores everyone
    // (Discover); the default keeps only people who can teach you something
    // (Matches). Results are cached per snapshot version — see MatchService.
    if (method.equals("GET") && path.equals("/api/matches")) {
      String userId = Js.str(request.getParameter("userId"));
      if (userId.isEmpty()) {
        throw ApiException.badRequest("userId is required");
      }
      boolean all = "all".equals(request.getParameter("mode"));
      return respond(matchService.matches(userId, all), 200);
    }

    // D4: sandbox premium — store-wide revenue, or one user's subscription
    // status when ?userId= is present (both derived from the ledger).
    if (method.equals("GET") && path.equals("/api/premium")) {
      String userId = Js.str(request.getParameter("userId"));
      return respond(userId.isEmpty() ? paymentService.stats() : paymentService.status(userId),
          200);
    }

    // The @Scheduled reminder job's read-only view (multithreading deliverable).
    if (method.equals("GET") && path.equals("/api/reminders")) {
      return respond(reminderJob.stats(), 200);
    }

    // Same gate as Node: everything that is not GET /api/state must be a
    // write, or the request never reaches a handler.
    if (!method.equals("POST") && !method.equals("PATCH")) {
      throw ApiException.methodNotAllowed("Method not allowed");
    }

    // Development-only data wipe: gated by `twofold.reset.enabled` (default
    // false). `scripts/dev.mjs` sets TWOFOLD_RESET_ENABLED=true for the local
    // loop and the test suite enables it explicitly; a deployment that does
    // nothing answers 403 here.
    if (method.equals("POST") && path.equals("/api/reset")) {
      if (!resetEnabled) {
        throw ApiException.forbidden("reset is disabled on this deployment");
      }
      Database reset = state.reset();
      // The wiped profiles take their credentials and sessions with them; the
      // reseeded students get no accounts (there is no demo password).
      auth.clearAllCredentials();
      return respond(reset, 200);
    }

    // Task 2 enforcement: every mutating endpoint identifies its actor from the
    // session cookie, never from a client-supplied id. No session → 401.
    if (actor == null) {
      throw ApiException.unauthorized("authentication required");
    }

    Map<String, Object> body = readBody(request);

    // ------------------------------------------------------------ users
    if (method.equals("POST") && path.equals("/api/users")) {
      String name = Js.str(body.get("name"));
      String email = Js.str(body.get("email"));
      if (name.isEmpty() || email.isEmpty()) {
        throw ApiException.badRequest("name and email are required");
      }
      String handle = Js.handleFor(name);
      return respond(state.mutate(db -> {
        // Both uniqueness rules used to be schema violations → bare 500.
        if (db.users().stream().anyMatch(u -> u.email().equalsIgnoreCase(email))) {
          throw ApiException.badRequest("email is already registered: " + email);
        }
        if (db.users().stream().anyMatch(u -> u.handle().equals(handle))) {
          throw ApiException.badRequest("handle is already taken: " + handle);
        }
        User created = new User(
            "u_" + Js.uid(),
            name,
            handle,
            email,
            orDefault(Js.str(body.get("branch")), "Undeclared"),
            orDefault(Js.str(body.get("year")), "1st year"),
            orDefault(Js.str(body.get("bio")), "New to Twofold — here to trade skills."),
            List.of(),
            List.of(),
            List.of(),
            "Either",
            Js.nowIso(),
            (int) (Math.random() * 360),
            false);
        List<User> users = new ArrayList<>(db.users());
        users.add(created);
        return StateService.bump(db.withUsers(users));
      }), 201);
    }

    if (method.equals("PATCH") && "users".equals(part(parts, 1)) && part(parts, 2) != null) {
      String id = part(parts, 2);
      requireSelf(id, actor, "you can only edit your own profile");
      return respond(state.mutate(db -> {
        int idx = indexOfUser(db, id);

        // Skill replacement touches foreign keys, so an unknown id would have
        // been an FK 500 in Node; scalar-only patches stay the contract's
        // 200 no-op that still bumps (UPDATE affects 0 rows, bump still runs).
        boolean skillPatch = body.containsKey("teaches") || body.containsKey("wants");
        if (idx < 0) {
          if (skillPatch) {
            throw ApiException.badRequest("no such user: " + id);
          }
          return StateService.bump(db);
        }

        // Constraints are only evaluated for rows that exist (Node's UPDATE
        // semantics), so format is validated here, not before the lookup.
        if (body.containsKey("format")) {
          String format = Js.str(body.get("format"));
          if (!FORMATS.contains(format)) {
            throw ApiException.badRequest("format must be Online, On campus or Either");
          }
        }

        String name = body.containsKey("name") ? Js.str(body.get("name")) : null;
        String branch = body.containsKey("branch") ? Js.str(body.get("branch")) : null;
        String year = body.containsKey("year") ? Js.str(body.get("year")) : null;
        String bio = body.containsKey("bio") ? Js.str(body.get("bio")) : null;
        String format = body.containsKey("format") ? Js.str(body.get("format")) : null;
        List<String> availability = body.containsKey("availability")
            ? Js.strArray(body.get("availability")) : null;
        // A non-array value is ignored outright — Node's Array.isArray guard.
        List<ExchangeSkill> teaches = body.containsKey("teaches")
            ? skillEntries(body.get("teaches"), db) : null;
        List<ExchangeSkill> wants = body.containsKey("wants")
            ? skillEntries(body.get("wants"), db) : null;

        User before = db.users().get(idx);
        User after = new User(
            before.id(),
            name != null ? name : before.name(),
            before.handle(),   // derived once, at creation — Node never re-derives
            before.email(),    // PATCH has no email field
            branch != null ? branch : before.branch(),
            year != null ? year : before.year(),
            bio != null ? bio : before.bio(),
            teaches != null ? teaches : before.teaches(),
            wants != null ? wants : before.wants(),
            availability != null ? availability : before.availability(),
            format != null ? format : before.format(),
            before.joinedAt(),
            before.hue(),
            before.verified());

        List<User> users = new ArrayList<>(db.users());
        users.set(idx, after);
        return StateService.bump(db.withUsers(users));
      }), 200);
    }

    // --------------------------------------------------------- sessions
    if (method.equals("POST") && path.equals("/api/sessions")) {
      String teacherId = Js.str(body.get("teacherId"));
      String learnerId = Js.str(body.get("learnerId"));
      String skillId = Js.str(body.get("skillId"));
      String topic = Js.str(body.get("topic"));
      if (teacherId.isEmpty() || learnerId.isEmpty() || skillId.isEmpty() || topic.isEmpty()) {
        throw ApiException.badRequest("teacherId, learnerId, skillId and topic are required");
      }
      if (!learnerId.equals(actor)) {
        throw ApiException.forbidden("you can only request sessions as yourself");
      }
      List<String> slots = Js.strArray(body.get("proposedSlots"));
      if (slots.isEmpty()) {
        throw ApiException.badRequest("at least one proposed slot is required");
      }
      String note = Js.str(body.get("note"));
      return respond(state.mutate(db -> {
        // FKs and the self-session CHECK were schema violations → 500.
        if (findUser(db, teacherId) == null) {
          throw ApiException.badRequest("no such teacher: " + teacherId);
        }
        if (findUser(db, learnerId) == null) {
          throw ApiException.badRequest("no such learner: " + learnerId);
        }
        if (teacherId.equals(learnerId)) {
          throw ApiException.badRequest("teacherId and learnerId must be different");
        }
        if (!hasSkill(db, skillId)) {
          throw ApiException.badRequest("no such skill: " + skillId);
        }
        Session created = new Session(
            "s_" + Js.uid(), teacherId, learnerId, skillId, topic,
            "proposed", slots, null, Js.nowIso(), null,
            note.isEmpty() ? null : note);
        List<Session> sessions = new ArrayList<>(db.sessions());
        sessions.add(created);
        return StateService.bump(db.withSessions(sessions));
      }), 201);
    }

    if (method.equals("POST") && "sessions".equals(part(parts, 1))
        && part(parts, 2) != null && part(parts, 3) != null) {
      String id = part(parts, 2);
      String action = part(parts, 3);
      // Only the two participants may move a session. An unknown id keeps the
      // contract's 200-with-bump quirk (there is no row to protect).
      Session target = findSession(state.snapshot(), id);
      if (target != null && !target.teacherId().equals(actor)
          && !target.learnerId().equals(actor)) {
        throw ApiException.forbidden("you are not a participant in this session");
      }
      return switch (action) {
        case "accept" -> {
          String slot = Js.str(body.get("slot"));
          if (slot.isEmpty()) {
            throw ApiException.badRequest("slot is required");
          }
          yield respond(state.mutate(db -> sessionAction(db, id, "confirmed", slot, null)), 200);
        }
        case "decline" ->
            respond(state.mutate(db -> sessionAction(db, id, "declined", null, null)), 200);
        case "cancel" ->
            respond(state.mutate(db -> sessionAction(db, id, "cancelled", null, null)), 200);
        case "complete" ->
            respond(state.mutate(db -> sessionAction(db, id, "completed", null, Js.nowIso())), 200);
        default -> throw ApiException.notFound("Unknown session action: " + action);
      };
    }

    // ---------------------------------------------------------- reviews
    if (method.equals("POST") && path.equals("/api/reviews")) {
      String sessionId = Js.str(body.get("sessionId"));
      String fromUserId = Js.str(body.get("fromUserId"));
      String toUserId = Js.str(body.get("toUserId"));
      double rating = Js.jsNumber(body.get("rating"));
      if (sessionId.isEmpty() || fromUserId.isEmpty() || toUserId.isEmpty() || Js.falsy(rating)) {
        throw ApiException.badRequest("sessionId, fromUserId, toUserId and rating are required");
      }
      if (rating < 1 || rating > 5) {
        throw ApiException.badRequest("rating must be between 1 and 5");
      }
      requireSelf(fromUserId, actor, "you can only review as yourself");
      String text = Js.str(body.get("text"));
      String role = "learned".equals(body.get("role")) ? "learned" : "taught";
      int rounded = (int) Math.round(rating);
      return respond(state.mutate(db -> {
        if (findSession(db, sessionId) == null) {
          throw ApiException.badRequest("no such session: " + sessionId);
        }
        if (findUser(db, fromUserId) == null) {
          throw ApiException.badRequest("no such user: " + fromUserId);
        }
        if (findUser(db, toUserId) == null) {
          throw ApiException.badRequest("no such user: " + toUserId);
        }
        boolean duplicate = db.reviews().stream().anyMatch(r ->
            r.sessionId().equals(sessionId) && r.fromUserId().equals(fromUserId));
        if (duplicate) {
          return db;   // no insert, no bump — but still 201 (UNIQUE pre-check)
        }
        Review created = new Review(
            "r_" + Js.uid(), sessionId, fromUserId, toUserId, rounded,
            text.isEmpty() ? "No comment left." : text, Js.nowIso(), role);
        List<Review> reviews = new ArrayList<>(db.reviews());
        reviews.add(created);
        return StateService.bump(db.withReviews(reviews));
      }), 201);
    }

    // --------------------------------------------------------- messages
    if (method.equals("POST") && path.equals("/api/messages")) {
      String fromUserId = Js.str(body.get("fromUserId"));
      String toUserId = Js.str(body.get("toUserId"));
      String message = Js.str(body.get("body"));
      if (fromUserId.isEmpty() || toUserId.isEmpty() || message.isEmpty()) {
        throw ApiException.badRequest("fromUserId, toUserId and body are required");
      }
      requireSelf(fromUserId, actor, "you can only send messages as yourself");
      return respond(state.mutate(db -> {
        if (findUser(db, fromUserId) == null) {
          throw ApiException.badRequest("no such user: " + fromUserId);
        }
        if (findUser(db, toUserId) == null) {
          throw ApiException.badRequest("no such user: " + toUserId);
        }
        var created = new Message(
            "m_" + Js.uid(), fromUserId, toUserId, message, Js.nowIso(),
            List.of(fromUserId));   // the sender has, by definition, read it
        List<Message> messages = new ArrayList<>(db.messages());
        messages.add(created);
        return StateService.bump(db.withMessages(messages));
      }), 201);
    }

    if (method.equals("POST") && path.equals("/api/messages/read")) {
      String userId = Js.str(body.get("userId"));
      String partnerId = Js.str(body.get("partnerId"));
      if (userId.isEmpty() || partnerId.isEmpty()) {
        throw ApiException.badRequest("userId and partnerId are required");
      }
      requireSelf(userId, actor, "you can only mark your own messages read");
      return respond(state.mutate(db -> {
        boolean changed = false;
        List<Message> messages = new ArrayList<>(db.messages());
        for (int i = 0; i < messages.size(); i++) {
          var m = messages.get(i);
          if (m.fromUserId().equals(partnerId) && m.toUserId().equals(userId)
              && !m.readBy().contains(userId)) {
            List<String> readBy = new ArrayList<>(m.readBy());
            readBy.add(userId);
            messages.set(i, m.withReadBy(readBy));
            changed = true;
          }
        }
        // Idempotent re-run → same version (Node counted INSERT OR IGNORE).
        return changed ? StateService.bump(db.withMessages(messages)) : db;
      }), 200);
    }

    // ----------------------------------------------------------- premium (D4)
    if (method.equals("POST") && path.equals("/api/premium")) {
      String userId = Js.str(body.get("userId"));
      if (userId.isEmpty()) {
        throw ApiException.badRequest("userId is required");
      }
      requireSelf(userId, actor, "you can only buy Pro for yourself");
      String plan = Js.str(body.get("plan"));
      if (plan.isEmpty()) {
        throw ApiException.badRequest("plan is required");
      }
      String cardToken = Js.str(body.get("cardToken"));
      if (cardToken.isEmpty()) {
        throw ApiException.badRequest("cardToken is required");
      }
      // A decline escapes as PaymentFailedException → 402 via the advice,
      // before any state change — no bump, no ledger row (contract).
      return respond(paymentService.purchase(userId, plan, cardToken), 201);
    }

    throw ApiException.notFound("No such endpoint: " + method + " " + path);
  }

  /* -------------------------------------------------------- public stats */

  /**
   * The landing page's data source — the only read that does not need a
   * session. Deliberately narrow: aggregate counts, three featured profiles
   * reduced to presentational fields (name, handle, branch, year, hue, skills,
   * rating), one review quote and the hero match score.
   *
   * <p>It must never grow an email, a joined date or any credential material:
   * anything returned here is readable by every anonymous visitor.
   */
  private Map<String, Object> publicStats() {
    Database db = state.snapshot();

    List<User> featuredUsers = new ArrayList<>();
    for (String id : List.of("u_aarav", "u_riya", "u_ananya")) {
      db.users().stream().filter(u -> u.id().equals(id)).findFirst()
          .ifPresent(featuredUsers::add);
    }
    for (User candidate : db.users()) {
      if (featuredUsers.size() >= 3) {
        break;
      }
      if (!featuredUsers.contains(candidate)) {
        featuredUsers.add(candidate);
      }
    }

    List<Map<String, Object>> featured = new ArrayList<>();
    for (User u : featuredUsers) {
      Map<String, Object> card = new LinkedHashMap<>();
      card.put("id", u.id());
      card.put("name", u.name());
      card.put("handle", u.handle());
      card.put("branch", u.branch());
      card.put("year", u.year());
      card.put("hue", u.hue());
      card.put("verified", u.verified());
      card.put("teaches", u.teaches());
      card.put("wants", u.wants());
      card.put("rating", ratingFor(u.id(), db));
      featured.add(card);
    }

    int completed = (int) db.sessions().stream()
        .filter(s -> "completed".equals(s.status())).count();

    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("users", db.users().size());
    stats.put("skills", db.skills().size());
    stats.put("sessions", db.sessions().size());
    stats.put("completed", completed);
    stats.put("reviews", db.reviews().size());
    stats.put("rating", ratingForAll(db));
    stats.put("featured", featured);
    stats.put("quote", quote(db));
    stats.put("heroScore", heroScore());
    return stats;
  }

  /** One student's review standing, rounded to 1 dp like the client does. */
  private static Map<String, Object> ratingFor(String userId, Database db) {
    List<Review> reviews = db.reviews().stream()
        .filter(r -> r.toUserId().equals(userId)).toList();
    double average = reviews.stream().mapToInt(Review::rating).average().orElse(0);
    Map<String, Object> rating = new LinkedHashMap<>();
    rating.put("average", Math.round(average * 10) / 10.0);
    rating.put("count", reviews.size());
    return rating;
  }

  private static Map<String, Object> ratingForAll(Database db) {
    double average = db.reviews().stream().mapToInt(Review::rating).average().orElse(0);
    Map<String, Object> rating = new LinkedHashMap<>();
    rating.put("average", Math.round(average * 10) / 10.0);
    rating.put("count", db.reviews().size());
    return rating;
  }

  /** The longest seed review, reduced to a quote — names only, no ids or emails. */
  private static Map<String, Object> quote(Database db) {
    Review chosen = db.reviews().stream()
        .filter(r -> r.text() != null && r.text().length() > 60)
        .findFirst()
        .orElse(db.reviews().isEmpty() ? null : db.reviews().get(0));
    if (chosen == null) {
      return null;
    }
    Map<String, Object> quote = new LinkedHashMap<>();
    quote.put("text", chosen.text());
    quote.put("rating", chosen.rating());
    User from = findUser(db, chosen.fromUserId());
    User to = findUser(db, chosen.toUserId());
    quote.put("fromName", from == null ? "A student" : from.name());
    quote.put("toName", to == null ? "a classmate" : to.name());
    quote.put("createdAt", chosen.createdAt());
    return quote;
  }

  /**
   * The hero's match ring: the real score between the two featured students.
   * A missing seed row degrades to zero rather than failing the public page.
   */
  private int heroScore() {
    try {
      return matchService.matches("u_aarav", true).stream()
          .filter(m -> m.user().id().equals("u_riya"))
          .findFirst()
          .map(MatchEngine.MatchResult::score)
          .orElse(0);
    } catch (RuntimeException e) {
      return 0;
    }
  }

  /* --------------------------------------------------------------- auth (T2) */

  /**
   * The four auth routes. Register/login issue an HttpOnly session cookie and
   * return {@code {user, database}} so the client can paint immediately;
   * logout clears the cookie and deletes the row; me is the "am I signed in?"
   * probe the frontend boots with.
   */
  private ResponseEntity<?> authRoute(HttpServletRequest request, String method, String path)
      throws IOException {
    if (method.equals("POST") && path.equals("/api/auth/register")) {
      Map<String, Object> body = readBody(request);
      String name = Js.str(body.get("name"));
      String email = Js.str(body.get("email"));
      String password = Js.str(body.get("password"));
      if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
        throw ApiException.badRequest("name, email and password are required");
      }
      if (password.length() < 8) {
        throw ApiException.badRequest("password must be at least 8 characters");
      }
      String handle = Js.handleFor(name);
      Database created = state.mutate(db -> {
        if (db.users().stream().anyMatch(u -> u.email().equalsIgnoreCase(email))) {
          throw ApiException.badRequest("email is already registered: " + email);
        }
        if (db.users().stream().anyMatch(u -> u.handle().equals(handle))) {
          throw ApiException.badRequest("handle is already taken: " + handle);
        }
        User student = new User(
            "u_" + Js.uid(),
            name,
            handle,
            email,
            orDefault(Js.str(body.get("branch")), "Undeclared"),
            orDefault(Js.str(body.get("year")), "1st year"),
            orDefault(Js.str(body.get("bio")), "New to Twofold — here to trade skills."),
            List.of(),
            List.of(),
            List.of(),
            "Either",
            Js.nowIso(),
            (int) (Math.random() * 360),
            false);
        List<User> users = new ArrayList<>(db.users());
        users.add(student);
        return StateService.bump(db.withUsers(users));
      });
      User student = created.users().stream()
          .filter(u -> u.handle().equals(handle)).findFirst()
          .orElseThrow(() -> ApiException.badRequest("registration failed"));
      auth.createAccount(student.id(), password);
      AuthSession session = auth.issue(student.id());
      return respond(authPayload(student, created), 201, sessionCookie(session.token()));
    }

    if (method.equals("POST") && path.equals("/api/auth/login")) {
      Map<String, Object> body = readBody(request);
      String identifier = firstNonEmpty(
          Js.str(body.get("email")), Js.str(body.get("handle")), Js.str(body.get("identifier")));
      if (identifier.isEmpty()) {
        throw ApiException.badRequest("email or handle is required");
      }
      AuthSession session = auth.login(identifier, Js.str(body.get("password")));
      Database db = state.snapshot();
      User student = findUser(db, session.userId());
      if (student == null) {
        throw ApiException.unauthorized("invalid email or password");
      }
      return respond(authPayload(student, db), 200, sessionCookie(session.token()));
    }

    if (method.equals("POST") && path.equals("/api/auth/logout")) {
      auth.logout(sessionToken(request));
      Map<String, Object> ok = new LinkedHashMap<>();
      ok.put("ok", true);
      return respond(ok, 200, clearedCookie());
    }

    if (method.equals("GET") && path.equals("/api/auth/me")) {
      String actor = auth.userIdFor(sessionToken(request));
      User student = actor == null ? null : findUser(state.snapshot(), actor);
      if (student == null) {
        throw ApiException.unauthorized("not signed in");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("user", student);
      return respond(payload, 200);
    }

    if (!method.equals("POST") && !method.equals("GET")) {
      throw ApiException.methodNotAllowed("Method not allowed");
    }
    throw ApiException.notFound("No such endpoint: " + method + " " + path);
  }

  /** {@code {user, database}} — the login/register payload. */
  private static Map<String, Object> authPayload(User user, Database db) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("user", user);
    payload.put("database", db);
    return payload;
  }

  /** The session cookie value, or {@code null} for a logged-out request. */
  private static String sessionToken(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (AuthService.COOKIE_NAME.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  /**
   * HttpOnly + SameSite=Lax + Path=/: invisible to JavaScript, not sent on
   * cross-site POSTs. See {@link AuthService} for why a cookie over a bearer
   * token, and why no CSRF token is needed here.
   */
  private static String sessionCookie(String token) {
    return ResponseCookie.from(AuthService.COOKIE_NAME, token)
        .httpOnly(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(AuthService.SESSION_TTL)
        .build()
        .toString();
  }

  private static String clearedCookie() {
    return ResponseCookie.from(AuthService.COOKIE_NAME, "")
        .httpOnly(true)
        .sameSite("Lax")
        .path("/")
        .maxAge(Duration.ZERO)
        .build()
        .toString();
  }

  /** 403 when a body names someone other than the session's actor. */
  private static void requireSelf(String claimed, String actor, String message) {
    if (claimed != null && !claimed.isEmpty() && !claimed.equals(actor)) {
      throw ApiException.forbidden(message);
    }
  }

  private static String firstNonEmpty(String... values) {
    for (String value : values) {
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    return "";
  }

  /* ------------------------------------------------------------- helpers */

  /**
   * A session action write. Bumps even when the id matched nothing — Node's
   * {@code UPDATE ... WHERE id = ?} affects 0 rows and still bumps.
   */
  private static Database sessionAction(Database db, String id, String status,
      String slot, String completedAt) {
    List<Session> sessions = new ArrayList<>(db.sessions());
    for (int i = 0; i < sessions.size(); i++) {
      Session s = sessions.get(i);
      if (s.id().equals(id)) {
        sessions.set(i, s.withStatus(status, slot, completedAt));
        break;
      }
    }
    return StateService.bump(db.withSessions(sessions));
  }

  /**
   * Skill entries of a PATCH body: {@code null} when the key is not an array
   * (Node ignores those outright). Otherwise every entry is validated — an
   * unknown skill id used to be an FK 500 and a bad level used to be a
   * silently dropped row; both are 400s now (DEF-010). Duplicate skill ids in
   * one list keep the first occurrence, mirroring {@code INSERT OR IGNORE}.
   */
  private static List<ExchangeSkill> skillEntries(Object value, Database db) {
    if (!(value instanceof List<?> list)) {
      return null;
    }
    Set<String> known = new HashSet<>();
    db.skills().forEach(s -> known.add(s.id()));
    List<ExchangeSkill> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object entry : list) {
      String skillId = entry instanceof Map<?, ?> m ? Js.str(m.get("skillId")) : "";
      String level = entry instanceof Map<?, ?> m ? Js.str(m.get("level")) : "";
      if (!known.contains(skillId)) {
        throw ApiException.badRequest("no such skill: " + skillId);
      }
      if (!LEVELS.contains(level)) {
        throw ApiException.badRequest("level must be Beginner, Intermediate or Advanced");
      }
      if (seen.add(skillId)) {
        out.add(new ExchangeSkill(skillId, level));
      }
    }
    return out;
  }

  /**
   * The raw body, with Node's leniency: empty body → {@code {}}, malformed
   * JSON → {@code {}}, and any non-object document → {@code {}} (a JSON array
   * behaves like an empty body in every branch below, exactly as it did in
   * JavaScript, where {@code body.name} on an array is {@code undefined}).
   */
  private Map<String, Object> readBody(HttpServletRequest request) throws IOException {
    String raw = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (raw.isEmpty()) {
      return Map.of();
    }
    try {
      Object parsed = mapper.readValue(raw, Object.class);
      return parsed instanceof Map<?, ?> map ? asBody(map) : Map.of();
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asBody(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  private static <T> ResponseEntity<T> respond(T body, int status) {
    return ResponseEntity.status(status)
        .contentType(JSON_CT)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(body);
  }

  private static <T> ResponseEntity<T> respond(T body, int status, String setCookie) {
    return ResponseEntity.status(status)
        .contentType(JSON_CT)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.SET_COOKIE, setCookie)
        .body(body);
  }

  /** Node's {@code path.split('/').filter(Boolean)}. */
  private static List<String> split(String path) {
    List<String> parts = new ArrayList<>();
    for (String p : path.split("/")) {
      if (!p.isEmpty()) {
        parts.add(p);
      }
    }
    return parts;
  }

  /** {@code parts[i]} with Node's out-of-range {@code undefined}. */
  private static String part(List<String> parts, int i) {
    return i < parts.size() ? parts.get(i) : null;
  }

  private static String orDefault(String value, String fallback) {
    return value.isEmpty() ? fallback : value;
  }

  private static int indexOfUser(Database db, String id) {
    List<User> users = db.users();
    for (int i = 0; i < users.size(); i++) {
      if (users.get(i).id().equals(id)) {
        return i;
      }
    }
    return -1;
  }

  private static User findUser(Database db, String id) {
    int idx = indexOfUser(db, id);
    return idx < 0 ? null : db.users().get(idx);
  }

  private static Session findSession(Database db, String id) {
    return db.sessions().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
  }

  private static boolean hasSkill(Database db, String id) {
    return db.skills().stream().anyMatch(s -> s.id().equals(id));
  }
}
