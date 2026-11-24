package com.twofold.backend.domain;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A directional exchange: teacherId teaches skillId to learnerId.
 *
 * <p>{@code NON_NULL} mirrors the Node serializer exactly: the Node side maps SQL
 * NULLs to {@code undefined}, and {@code JSON.stringify} omits undefined keys — so
 * scheduledFor/completedAt/note are <em>absent</em> from the JSON until set.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Session(
    String id,
    String teacherId,
    String learnerId,
    String skillId,
    String topic,
    String status,
    List<String> proposedSlots,
    String scheduledFor,
    String createdAt,
    String completedAt,
    String note) {

  public Session {
    proposedSlots = proposedSlots == null ? List.of() : List.copyOf(proposedSlots);
  }

  public Session withStatus(String newStatus, String newScheduledFor, String newCompletedAt) {
    return new Session(id, teacherId, learnerId, skillId, topic, newStatus,
        proposedSlots, newScheduledFor != null ? newScheduledFor : scheduledFor,
        createdAt, newCompletedAt != null ? newCompletedAt : completedAt, note);
  }
}
