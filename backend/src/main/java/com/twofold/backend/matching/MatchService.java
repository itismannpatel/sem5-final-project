package com.twofold.backend.matching;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.User;
import com.twofold.backend.http.ApiException;
import com.twofold.backend.state.StateService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Cached access to {@link MatchEngine} (D3).
 *
 * <p>The cache is keyed by {@code mode:userId} and stamped with the snapshot
 * version it was computed from: any write bumps {@code version}, so the very
 * next request misses, recomputes once, and repopulates. Entries are bounded
 * (one per user per mode — never one per version), stale entries are never
 * served, and recomputation happens under no global lock, so concurrent
 * requests for different users compute in parallel — the engine itself is
 * pure and touches no shared mutable state.
 */
@Service
public class MatchService {

  private record Cached(int version, List<MatchEngine.MatchResult> results) {}

  private final StateService state;
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  public MatchService(StateService state) {
    this.state = state;
  }

  /**
   * Ranked matches for {@code userId}: everyone else who can teach them
   * something ({@code mode} absent), or every other student scored
   * ({@code mode=all}).
   *
   * @throws ApiException 400 when the user does not exist
   */
  public List<MatchEngine.MatchResult> matches(String userId, boolean all) {
    Database db = state.snapshot();
    User me = db.users().stream().filter(u -> u.id().equals(userId)).findFirst()
        .orElseThrow(() -> ApiException.badRequest("no such user: " + userId));

    String key = (all ? "all:" : "one:") + userId;
    Cached hit = cache.get(key);
    if (hit != null && hit.version() == db.version()) {
      return hit.results();
    }

    List<MatchEngine.MatchResult> results = all
        ? MatchEngine.scoreEveryone(me, db)
        : MatchEngine.computeMatches(me, db);
    cache.put(key, new Cached(db.version(), results));
    return results;
  }
}
