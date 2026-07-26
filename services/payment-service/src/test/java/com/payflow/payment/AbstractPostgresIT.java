package com.payflow.payment;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One real PostgreSQL, shared by every integration test in this service.
 *
 * <p><strong>Requires a running Docker daemon.</strong> Subclasses inherit {@code @Tag("docker")}, so
 * they run under {@code ./mvnw verify} and are skipped by {@code ./mvnw test} and by
 * {@code ./mvnw -Pno-docker verify}.
 *
 * <p>PostgreSQL, not H2. TESTING_STRATEGY.md forbids the substitution because what this schema relies
 * on — {@code NUMERIC(19,4)} arithmetic, partial indexes, {@code FOR UPDATE SKIP LOCKED},
 * {@code jsonb}, statement triggers — behaves differently or does not exist in H2, so an H2-backed
 * test would report success about a database nobody runs.
 *
 * <p>This is the documented Testcontainers singleton pattern rather than {@code @Testcontainers} plus
 * {@code @Container}: the static initialiser runs once per JVM, so one container serves every
 * subclass instead of one per test class. Nothing stops it explicitly — Testcontainers' own reaper
 * removes it when the JVM exits, and stopping it in an {@code @AfterAll} would tear it down for the
 * classes that still need it.
 *
 * <p>The image tag is here and only here. It must match {@code docker-compose.yml}; if the two drift,
 * the tests validate a different PostgreSQL version than the one developers run.
 */
@Tag("docker")
public abstract class AbstractPostgresIT {

    // Not PostgreSQLContainer<?>: Testcontainers 2.x dropped the self-referential type parameter that
    // the 1.x builder API needed, so the class is no longer generic.
    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine");

    static {
        POSTGRES.start();
    }
}
