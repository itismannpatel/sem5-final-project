package com.twofold.backend.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.twofold.backend.domain.Database;
import com.twofold.backend.domain.Message;
import com.twofold.backend.matching.MatchEngine;
import com.twofold.backend.matching.MatchService;
import com.twofold.backend.store.JdbcStorage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lock semantics under load — the {@link StateService} read/write lock and the
 * version-keyed {@link MatchService} cache. Traces TC-063…TC-065: concurrent
 * writers lose no updates, readers never see a version go backwards or a torn
 * snapshot, and the cache never serves a stale ranking.
 */
class ConcurrencyTest {

  private static final JsonMapper MAPPER = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();

  @TempDir
  Path tmp;

  private JdbcStorage store;
  private StateService state;

  @BeforeEach
  void setUp() {
    store = new JdbcStorage(tmp.resolve("state.db"), MAPPER);
    state = new StateService(store, MAPPER);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  /** Append one message through the public write path. */
  private void appendMessage(int writer, int seq) {
    state.mutate(db -> {
      List<Message> messages = new ArrayList<>(db.messages());
      messages.add(new Message("m_w" + writer + "_" + seq, "u_aarav", "u_kabir",
          "writer " + writer + " message " + seq, "2026-11-22T10:00:00.000Z",
          List.of("u_aarav")));
      return StateService.bump(db.withMessages(messages));
    });
  }

  @Test
  void concurrentWritersProduceExactVersionArithmeticAndNoLostUpdates() throws Exception {
    int writers = 8;
    int perWriter = 25;
    int startVersion = state.snapshot().version();
    int startMessages = state.snapshot().messages().size();

    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (int w = 0; w < writers; w++) {
      int writer = w;
      futures.add(pool.submit(() -> {
        start.await();
        for (int i = 0; i < perWriter; i++) {
          appendMessage(writer, i);
        }
        return null;
      }));
    }
    start.countDown();
    for (Future<?> f : futures) {
      f.get(30, TimeUnit.SECONDS);   // rethrows any worker failure
    }
    pool.shutdown();

    Database end = state.snapshot();
    assertThat(end.version()).isEqualTo(startVersion + writers * perWriter);
    assertThat(end.messages()).hasSize(startMessages + writers * perWriter);
    assertThat(end.messages()).filteredOn(m -> m.id().startsWith("m_w"))
        .hasSize(writers * perWriter);
  }

  @Test
  void concurrentReadersAlwaysSeeAMonotonicVersionAndAWholeSnapshot() throws Exception {
    int readers = 4;
    int samples = 50;
    AtomicBoolean writing = new AtomicBoolean(true);

    ExecutorService pool = Executors.newFixedThreadPool(readers + 2);
    // Two writers keep the version moving while readers sample.
    for (int w = 0; w < 2; w++) {
      int writer = w;
      pool.submit(() -> {
        for (int i = 0; i < 100 && writing.get(); i++) {
          appendMessage(writer, i);
        }
        return null;
      });
    }

    List<Callable<List<Integer>>> tasks = new ArrayList<>();
    for (int r = 0; r < readers; r++) {
      tasks.add(() -> {
        List<Integer> versions = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
          Database snapshot = state.snapshot();
          // A torn snapshot would expose a missing collection.
          assertThat(snapshot.users()).isNotNull();
          assertThat(snapshot.skills()).isNotNull();
          assertThat(snapshot.sessions()).isNotNull();
          assertThat(snapshot.reviews()).isNotNull();
          assertThat(snapshot.messages()).isNotNull();
          assertThat(snapshot.payments()).isNotNull();
          versions.add(snapshot.version());
        }
        return versions;
      });
    }

    List<Future<List<Integer>>> results = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);
    writing.set(false);
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);

    for (Future<List<Integer>> result : results) {
      List<Integer> versions = result.get();
      assertThat(versions).hasSize(samples);
      for (int i = 1; i < versions.size(); i++) {
        assertThat(versions.get(i)).isGreaterThanOrEqualTo(versions.get(i - 1));
      }
    }
  }

  @Test
  void matchCacheServesTheSameVersionAndRecomputesAfterAWrite() {
    MatchService matches = new MatchService(state);

    List<MatchEngine.MatchResult> first = matches.matches("u_aarav", false);
    List<MatchEngine.MatchResult> second = matches.matches("u_aarav", false);
    assertThat(second).isSameAs(first);   // same version → cached instance

    int before = state.snapshot().version();
    state.mutate(db -> {
      List<com.twofold.backend.domain.User> users = new ArrayList<>(db.users());
      for (int i = 0; i < users.size(); i++) {
        var u = users.get(i);
        if (u.id().equals("u_kabir")) {
          users.set(i, u.withSkills(List.of(), List.of()));
        }
      }
      return StateService.bump(db.withUsers(users));
    });
    assertThat(state.snapshot().version()).isEqualTo(before + 1);

    List<MatchEngine.MatchResult> third = matches.matches("u_aarav", false);
    assertThat(third).isNotSameAs(first);          // cache miss after the bump
    assertThat(third).noneMatch(m -> m.user().id().equals("u_kabir"));
  }

  @Test
  void parallelMatchRequestsForTheSameUserAgreeAndDoNotThrow() throws Exception {
    MatchService matches = new MatchService(state);
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);

    List<Future<String>> results = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      results.add(pool.submit(() -> {
        start.await();
        return MAPPER.writeValueAsString(matches.matches("u_aarav", true));
      }));
    }
    start.countDown();

    String expected = results.get(0).get(30, TimeUnit.SECONDS);
    for (Future<String> result : results) {
      assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
    }
    pool.shutdown();
  }
}
