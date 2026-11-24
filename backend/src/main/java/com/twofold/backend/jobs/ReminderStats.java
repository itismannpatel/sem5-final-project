package com.twofold.backend.jobs;

import java.util.List;

/**
 * What one reminder pass did, and where it wrote — the read model behind
 * {@code GET /api/reminders}.
 *
 * <p>Semantics: {@code runs} counts passes executed; {@code due} is what the
 * <em>most recent</em> pass found inside the window; {@code written} is
 * cumulative lines appended this process (dedupe means {@code due} can exceed
 * the lines a repeat pass adds); {@code recent} is the last ≤5 lines.
 */
public record ReminderStats(
    long runs,
    int due,
    long written,
    String lastRunAt,
    int windowHours,
    String outbox,
    List<String> recent) {}
