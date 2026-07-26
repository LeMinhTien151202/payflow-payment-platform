package com.payflow.payment.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the {@link Clock} that every timestamp in this service is read from.
 *
 * <p>Nothing in the application layer calls {@code Instant.now()}. A payment's {@code createdAt}, its status
 * history entry and its outbox event must all agree, and a test that asserts on them needs a time it controls;
 * both follow from time being an injected dependency rather than a static call.
 */
@Configuration
class ClockConfig {

    /**
     * UTC, not the system default zone. The database columns are {@code TIMESTAMPTZ} and every timestamp
     * crossing a service boundary is an {@code Instant}, so a local zone would only be a chance for the host's
     * configuration to leak into stored data.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
