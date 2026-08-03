package com.payflow.notification;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Tag("docker")
@ActiveProfiles("test")
public abstract class AbstractNotificationRuntimeIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine");

    static {
        POSTGRES.start();
    }
}
