package com.twofold.backend.domain;

/** One skill entry on a user: {@code {"skillId": ..., "level": ...}}. */
public record ExchangeSkill(String skillId, String level) {}
