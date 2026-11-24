package com.twofold.backend.jobs;

import com.twofold.backend.store.JdbcStorage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * The reminder job's append-only JSONL file — the "outbox" half of an
 * outbox/worker split: the scheduled thread only ever <em>appends</em> here,
 * never touches API state, so a reminder pass cannot race a write and can
 * never bump {@code version}.
 *
 * <p>File location follows the DEF-005 lesson: anchored on the <em>module</em>
 * location, beside the SQLite database ({@code backend/twofold.db}), never
 * {@code process.cwd()}. Override with {@code twofold.reminder.outbox}
 * (env-style property) for tests or the cloud instance.
 *
 * <p>{@code recent} keeps the last few lines in memory so
 * {@code GET /api/reminders} can show what was written without re-reading
 * the file from the HTTP threads.
 */
@Service
public class ReminderOutbox {

  private final Path file;
  private final ObjectMapper mapper;
  private final Object ioLock = new Object();
  private final ArrayDeque<String> recent = new ArrayDeque<>();

  public ReminderOutbox(ObjectMapper mapper,
      @Value("${twofold.reminder.outbox:}") String override) {
    this.mapper = mapper;
    // Same anchor as the state file (DEF-012) — one place decides where
    // runtime data lives.
    this.file = override.isBlank()
        ? JdbcStorage.defaultFile().resolveSibling("reminders-outbox.jsonl")
        : Path.of(override);
  }

  /** Append one JSON document per line. Returns how many lines were written. */
  public int append(List<String> lines) {
    if (lines.isEmpty()) {
      return 0;
    }
    synchronized (ioLock) {
      try {
        Path parent = file.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        for (String line : lines) {
          recent.addLast(line);
          if (recent.size() > 5) {
            recent.removeFirst();
          }
        }
        return lines.size();
      } catch (IOException e) {
        throw new UncheckedIOException("cannot append reminders to " + file, e);
      }
    }
  }

  /** Where the outbox lives — surfaced by {@code GET /api/reminders}. */
  public String path() {
    return file.toString();
  }

  /** The last ≤5 lines written by this process (newest last). */
  public List<String> recent() {
    synchronized (ioLock) {
      return List.copyOf(recent);
    }
  }

  /** Exposed for tests: serialize one record the same way lines are written. */
  public String render(Object line) {
    return mapper.writeValueAsString(line);
  }
}
