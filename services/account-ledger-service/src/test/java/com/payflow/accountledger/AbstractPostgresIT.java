package com.payflow.accountledger;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shared PostgreSQL 17 container. Compiled but excluded by the no-docker profile. */
@Tag("docker")
@TestPropertySource(
        properties = {
            "payflow.payment-consumer.enabled=false",
            "payflow.refund-consumer.enabled=false",
            "payflow.outbox.enabled=false"
        })
public abstract class AbstractPostgresIT {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine");

    static {
        POSTGRES.start();
    }
}
