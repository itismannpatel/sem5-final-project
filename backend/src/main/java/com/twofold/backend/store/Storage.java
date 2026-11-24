package com.twofold.backend.store;

import com.twofold.backend.domain.Database;

/**
 * Persistence seam (Task 1 / ADR-002): the local SQLite store behind this
 * interface today, a managed Postgres store on the cloud later — the service
 * layer never sees either.
 */
public interface Storage {

  /** The persisted snapshot, or {@code null} when the database has no state yet. */
  Database load();

  /**
   * Persist a snapshot. Implementations must be all-or-nothing: a reader never
   * sees half a write (the JDBC store does every table in one transaction).
   */
  void save(Database state);

  /** Drop persisted state so the next {@link #load()} returns {@code null}. */
  void clear();
}
