package com.twofold.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mapper this service builds its world from: the domain model is records,
 * and the seed document is trusted-but-lenient — an extra key in a future
 * seed edit must not take the server down.
 *
 * <p>Declared as the application's {@code ObjectMapper} so Spring Boot's
 * auto-configuration backs off and every side (snapshot serialization, body
 * parsing, file storage) speaks through one mapper.
 */
@Configuration
public class JacksonConfig {

  @Bean
  public JsonMapper twofoldMapper() {
    return JsonMapper.builder()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build();
  }
}
