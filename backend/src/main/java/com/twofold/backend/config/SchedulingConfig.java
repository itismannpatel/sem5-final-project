package com.twofold.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The background-job thread pool (the assignment's multithreading story,
 * made explicit): {@code @EnableScheduling} picks up this
 * {@link ThreadPoolTaskScheduler} bean, so reminder passes run on their own
 * named threads ({@code twofold-job-*}, pool of 2) instead of competing with
 * Tomcat's HTTP worker threads.
 *
 * <p>Contention between the two worlds is exactly one lock: the scheduler
 * takes a <em>read</em> lock on the snapshot ({@code StateService.snapshot})
 * while HTTP writes take the write lock — the concurrency tests exercise
 * that overlap directly.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

  @Bean
  public ThreadPoolTaskScheduler taskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setThreadNamePrefix("twofold-job-");
    scheduler.setDaemon(true);
    scheduler.initialize();
    return scheduler;
  }
}
