package com.twofold.backend.config;

import com.twofold.backend.store.Db;
import com.twofold.backend.store.JdbcStorage;
import com.twofold.backend.store.Storage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * The persistence seam (Task 1): one SQLite {@link Db} in the backend module,
 * {@link JdbcStorage} over it, and a one-way import of the retired JSON store.
 *
 * <p>Location is module-anchored by default (DEF-005: never derive writable
 * paths from the process working directory) and overridable with
 * {@code twofold.store} (env {@code TWOFOLD_STORE}) for tests and containers.
 * {@code twofold.db} does the same for the database file itself.
 */
@Configuration
public class StorageConfig {

  private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

  @Bean(destroyMethod = "close")
  public Db db(@Value("${twofold.db:}") String dbPath) {
    Path file = dbPath.isBlank() ? JdbcStorage.defaultFile() : Path.of(dbPath);
    log.info("twofold db: {}", file.toAbsolutePath());
    return new Db(file);
  }

  @Bean
  public Storage storage(Db db, ObjectMapper mapper,
      @Value("${twofold.store:}") String legacyPath) {
    JdbcStorage storage = new JdbcStorage(db, mapper);
    Path legacy = legacyPath.isBlank() ? JdbcStorage.anchorDirectory()
        .resolve("twofold-data").resolve("state.json") : Path.of(legacyPath);
    if (Files.exists(legacy)) {
      storage.migrateFromJson(legacy);
    }
    return storage;
  }
}
