package com.payflow.reporting;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared real PostgreSQL database for reporting rebuild evidence. */
@Tag("docker")
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine");

    static {
        POSTGRES.start();
    }
}
