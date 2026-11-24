package com.twofold.backend.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The local SQLite database (Task 1): one connection, one file, schema
 * migrations tracked by {@code PRAGMA user_version}.
 *
 * <p>Why SQLite: it is a real, queryable, durable database with no server to
 * install, so {@code npm run dev} stays one command and the demo stays offline.
 * The file lives outside {@code target/} so a rebuild cannot wipe runtime data.
 *
 * <p><b>Migrations, not Flyway.</b> Flyway would add a dependency and a second
 * file layout for two scripts; {@code PRAGMA user_version} gives the same
 * versioned, forward-only history with no install step. The tradeoff is that
 * checksums/undo are ours to look after — acceptable for a local, single-writer
 * instance, and the scripts are plain {@code V#__name.sql} files so moving to
 * Flyway later is a copy, not a rewrite.
 *
 * <p>Connections are opened with WAL journalling (readers do not block the one
 * writer), {@code synchronous=FULL} (a commit survives power loss, which is the
 * durability claim), foreign keys on, and a busy timeout so a concurrent
 * credential read waits instead of failing.
 */
public final class Db implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Db.class);

  /** Forward-only migration history; index + 1 is the schema version. */
  private static final String[] MIGRATIONS = {
      "db/migration/V1__domain.sql",
      "db/migration/V2__auth.sql",
  };

  private final Path file;
  private final Connection connection;

  public Db(Path file) {
    this.file = file.toAbsolutePath();
    try {
      Path parent = this.file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.file);
      // One Statement per PRAGMA: sqlite-jdbc finalises a statement's native
      // pointer after a non-query execute, so a reused Statement fails on the
      // second call (seen as "The prepared statement has been finalized").
      for (String pragma : new String[] {
          "PRAGMA journal_mode=WAL",
          // NORMAL is the standard WAL setting: a committed transaction survives
          // an application crash; only an OS/power failure can drop the last
          // commit. FULL cost ~10 ms per whole-document write on this laptop and
          // pushed reader p95 past its target under load (see TEST_SUMMARY).
          "PRAGMA synchronous=NORMAL",
          "PRAGMA foreign_keys=ON",
          "PRAGMA busy_timeout=5000"}) {
        try (Statement st = connection.createStatement()) {
          st.execute(pragma);
        }
      }
      migrate();
    } catch (SQLException | IOException e) {
      throw new IllegalStateException("cannot open database " + this.file, e);
    }
  }

  /** The single connection; callers synchronise on this {@code Db} instance. */
  public Connection connection() {
    return connection;
  }

  public Path file() {
    return file;
  }

  public int schemaVersion() {
    synchronized (this) {
      try (Statement st = connection.createStatement();
          ResultSet rs = st.executeQuery("PRAGMA user_version")) {
        return rs.next() ? rs.getInt(1) : 0;
      } catch (SQLException e) {
        throw new IllegalStateException("cannot read schema version", e);
      }
    }
  }

  private void migrate() throws SQLException, IOException {
    int current = schemaVersion();
    for (int version = current + 1; version <= MIGRATIONS.length; version++) {
      String script = readResource(MIGRATIONS[version - 1]);
      log.info("twofold db: applying {} (schema v{})", MIGRATIONS[version - 1], version);
      connection.setAutoCommit(false);
      try {
        // Strip `--` comment lines before splitting: a comment may contain a
        // semicolon, and a comment-only chunk is not valid SQL to execute.
        String executable = script.replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : executable.split(";")) {
          if (!statement.isBlank()) {
            // A fresh Statement per statement: sqlite-jdbc finalises the native
            // pointer after a non-query execute, so reusing one object fails.
            try (Statement st = connection.createStatement()) {
              st.execute(statement);
            }
          }
        }
        try (Statement st = connection.createStatement()) {
          st.execute("PRAGMA user_version = " + version);
        }
        connection.commit();
      } catch (SQLException e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  private static String readResource(String resource) throws IOException {
    try (InputStream in = Db.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("missing schema resource " + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Override
  public synchronized void close() {
    try {
      connection.close();
    } catch (SQLException e) {
      log.warn("twofold db: close failed", e);
    }
  }
}
