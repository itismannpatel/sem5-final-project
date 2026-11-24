package com.twofold.backend.domain;

import java.util.List;

/**
 * The whole snapshot document the client renders from — the exact JSON shape of
 * {@code GET /api/state} and of every mutating response.
 *
 * <p>Immutable: every write produces a new {@code Database} with the changed
 * collection replaced, so a snapshot reference taken under the lock stays
 * consistent even if serialization happens after the lock is released.
 * Key order matches the Node contract and its extension: version, users,
 * skills, sessions, reviews, messages, payments ({@code payments} added with
 * the sandbox premium feature, D4 — the Node reference had no payments).
 */
public record Database(
    int version,
    List<User> users,
    List<Skill> skills,
    List<Session> sessions,
    List<Review> reviews,
    List<Message> messages,
    List<Payment> payments) {

  public Database {
    users = users == null ? List.of() : List.copyOf(users);
    skills = skills == null ? List.of() : List.copyOf(skills);
    sessions = sessions == null ? List.of() : List.copyOf(sessions);
    reviews = reviews == null ? List.of() : List.copyOf(reviews);
    messages = messages == null ? List.of() : List.copyOf(messages);
    payments = payments == null ? List.of() : List.copyOf(payments);
  }

  public Database withVersion(int newVersion) {
    return new Database(newVersion, users, skills, sessions, reviews, messages, payments);
  }

  public Database withUsers(List<User> newUsers) {
    return new Database(version, newUsers, skills, sessions, reviews, messages, payments);
  }

  public Database withSessions(List<Session> newSessions) {
    return new Database(version, users, skills, newSessions, reviews, messages, payments);
  }

  public Database withReviews(List<Review> newReviews) {
    return new Database(version, users, skills, sessions, newReviews, messages, payments);
  }

  public Database withMessages(List<Message> newMessages) {
    return new Database(version, users, skills, sessions, reviews, newMessages, payments);
  }

  public Database withPayments(List<Payment> newPayments) {
    return new Database(version, users, skills, sessions, reviews, messages, newPayments);
  }
}
