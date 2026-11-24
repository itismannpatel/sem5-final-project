package com.twofold.backend.domain;

import java.util.List;

/**
 * A direct message plus its read receipts. {@code readBy} always contains at
 * least the sender (sender auto-reads their own message at insert time).
 * Key order matches the Node contract: id, fromUserId, toUserId, body,
 * createdAt, readBy.
 */
public record Message(
    String id,
    String fromUserId,
    String toUserId,
    String body,
    String createdAt,
    List<String> readBy) {

  public Message {
    readBy = readBy == null ? List.of() : List.copyOf(readBy);
  }

  public Message withReadBy(List<String> newReadBy) {
    return new Message(id, fromUserId, toUserId, body, createdAt, newReadBy);
  }
}
