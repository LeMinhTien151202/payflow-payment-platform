package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Foundation checks for payment-service against a real PostgreSQL: migrations, schema ownership,
 * readiness, and the security posture of the fully assembled application.
 *
 * <p>Requires Docker — see {@link AbstractPostgresIT}. Because the whole class is skipped by
 * {@code ./mvnw -Pno-docker verify}, the security and error-contract rules are also covered in
 * {@code PaymentErrorContractTest}, where they stay provable without a daemon.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentServiceFoundationIT extends AbstractPostgresIT {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HealthEndpoint healthEndpoint;

    private final TestRestTemplate restTemplate = new TestRestTemplate();

    /**
     * That the context started at all is part of the assertion: {@code ddl-auto=validate} makes
     * Hibernate compare its mappings against the migrated schema and fail startup on a mismatch.
     */
    @Test
    @DisplayName("Flyway applies every migration, in order, with no gap")
    void everyMigrationIsApplied() {
        List<String> versions =
                jdbcTemplate.queryForList(
                        "SELECT version FROM payment.flyway_schema_history WHERE success = true"
                                + " AND version IS NOT NULL ORDER BY installed_rank",
                        String.class);

        // containsExactly, not contains: the order is the assertion. A repaired or out-of-order
        // history is how one environment ends up with a schema no migration file describes.
        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    @DisplayName("migrations run in the two schemas this service owns")
    void migrationRunsInTheOwnedSchemas() {
        List<String> schemas =
                jdbcTemplate.queryForList(
                        "SELECT nspname FROM pg_namespace WHERE nspname IN ('payment', 'merchant')"
                                + " ORDER BY nspname",
                        String.class);

        assertThat(schemas).containsExactly("merchant", "payment");
    }

    @Test
    @DisplayName("schema ownership is recorded in the database, not only in documentation")
    void schemaOwnershipIsDocumented() {
        String paymentComment =
                jdbcTemplate.queryForObject(
                        "SELECT obj_description('payment'::regnamespace, 'pg_namespace')",
                        String.class);
        String merchantComment =
                jdbcTemplate.queryForObject(
                        "SELECT obj_description('merchant'::regnamespace, 'pg_namespace')",
                        String.class);

        assertThat(paymentComment).contains("payment-service");
        assertThat(merchantComment).contains("merchant module");
    }

    /**
     * Locks the table set rather than checking that specific tables exist.
     *
     * <p>Phase 1A deliberately omits columns and tables whose meaning depends on a decision still
     * {@code OPEN} in OPEN_DECISIONS.md. A table appearing here that nobody listed is the visible
     * symptom of that gate being bypassed, and {@code containsExactly} is what makes it visible.
     */
    @Test
    @DisplayName("the schema contains exactly the tables Phase 1A is allowed to create")
    void schemaContainsOnlyThePhase1ATables() {
        assertThat(tableNamesIn("payment"))
                .containsExactly(
                        "flyway_schema_history",
                        "idempotency_records",
                        "outbox_events",
                        "payment_status_history",
                        "payments");

        assertThat(tableNamesIn("merchant")).containsExactly("merchants");
    }

    /**
     * The seed is a Flyway {@code afterMigrate} callback, which is silently skipped if its location is
     * missing from {@code spring.flyway.locations}. Nothing else would fail — tests would just start
     * finding an empty merchant catalog and reporting it as a business rule.
     */
    @Test
    @DisplayName("the local/test seed callback ran")
    void seedCallbackRan() {
        Integer merchants =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM merchant.merchants", Integer.class);

        assertThat(merchants).isGreaterThan(0);
    }

    private List<String> tableNamesIn(String schema) {
        return jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = ? AND table_type = 'BASE TABLE'"
                        + " ORDER BY table_name",
                String.class,
                schema);
    }

    /**
     * Readiness must depend on the database. A service reporting ready while it cannot reach
     * PostgreSQL would accept traffic it can only fail, which is the failure mode the Phase 0
     * observability gate exists to prevent.
     */
    @Test
    @DisplayName("readiness reflects database reachability")
    void readinessReflectsDatabase() {
        assertThat(healthEndpoint.healthForPath("db").getStatus()).isEqualTo(Status.UP);
        assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("health is reachable without a token so an orchestrator can probe it")
    void healthIsPubliclyReachable() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/actuator/health"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The complement of the check above: opening health must not have opened the rest of actuator.
     * {@code management.endpoints.web.exposure.include} lists health only, so anything else is absent
     * and the security chain denies it regardless.
     */
    @Test
    @DisplayName("other actuator endpoints are not exposed")
    void otherActuatorEndpointsAreNotExposed() {
        List<String> closed = List.of("/actuator/env", "/actuator/beans", "/actuator/configprops");

        for (String path : closed) {
            ResponseEntity<String> response = restTemplate.getForEntity(url(path), String.class);
            assertThat(response.getStatusCode())
                    .as("actuator endpoint %s must not be publicly readable", path)
                    .isIn(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("a business route still requires authentication in the full application")
    void businessRoutesRequireAuthentication() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/v1/payments/pay_1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("AUTH_UNAUTHENTICATED");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
