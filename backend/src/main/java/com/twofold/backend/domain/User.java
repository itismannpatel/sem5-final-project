package com.twofold.backend.domain;

import java.util.List;

/**
 * A student profile. JSON key order matches the Node contract exactly:
 * id, name, handle, email, branch, year, bio, teaches, wants, availability,
 * format, joinedAt, hue, verified.
 */
public record User(
    String id,
    String name,
    String handle,
    String email,
    String branch,
    String year,
    String bio,
    List<ExchangeSkill> teaches,
    List<ExchangeSkill> wants,
    List<String> availability,
    String format,
    String joinedAt,
    int hue,
    boolean verified) {

  public User {
    teaches = teaches == null ? List.of() : List.copyOf(teaches);
    wants = wants == null ? List.of() : List.copyOf(wants);
    availability = availability == null ? List.of() : List.copyOf(availability);
  }

  public User withName(String newName) {
    return new User(id, newName, handle, email, branch, year, bio, teaches, wants,
        availability, format, joinedAt, hue, verified);
  }

  public User withProfile(String name, String branch, String year, String bio, String format,
      List<String> availability) {
    return new User(id, name, handle, email, branch, year, bio, teaches, wants,
        availability, format, joinedAt, hue, verified);
  }

  public User withSkills(List<ExchangeSkill> newTeaches, List<ExchangeSkill> newWants) {
    return new User(id, name, handle, email, branch, year, bio, newTeaches, newWants,
        availability, format, joinedAt, hue, verified);
  }
}
