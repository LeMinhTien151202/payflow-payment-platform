package com.payflow.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
    void everyMigrationIsApplied() throws IOException {
        List<String> versions =
                jdbcTemplate.queryForList(
                        "SELECT version FROM payment.flyway_schema_history WHERE success = true"
                                + " AND version IS NOT NULL ORDER BY installed_rank",
                        String.class);

        // containsExactly, not contains: the order is the assertion. A repaired or out-of-order
        // history is how one environment ends up with a schema no migration file describes.
        //
        // The expectation is read from the migration files rather than hard-coded. A literal list
        // has to be edited by whoever adds V(n+1), and when they forget, the failure accuses Flyway
        // of applying a migration too many instead of accusing the test of being out of date.
        assertThat(versions).containsExactlyElementsOf(migrationVersionsOnClasspath());
    }

    private static List<String> migrationVersionsOnClasspath() throws IOException {
        Resource[] scripts =
                new PathMatchingResourcePatternResolver()
                        .getResources("classpath:db/migration/V*__*.sql");

        return Arrays.stream(scripts)
                .map(Resource::getFilename)
                .map(name -> name.substring(1, name.indexOf("__")))
                .sorted(Comparator.comparingInt(Integer::parseInt))
                .toList();
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
     * <p>Only tables backed by an accepted delivery decision may appear here. A table appearing
     * without being listed is the visible symptom of a governance gate being bypassed, and
     * {@code containsExactly} is what makes it visible.
     */
    @Test
    @DisplayName("the schema contains exactly the tables allowed through the recovery foundation")
    void schemaContainsOnlyTheAllowedTables() {
        assertThat(tableNamesIn("payment"))
                .containsExactly(
                        "audit_records",
                        "flyway_schema_history",
                        "idempotency_records",
                        "outbox_events",
                        "payment_sagas",
                        "payment_status_history",
                        "payments",
                        "processed_events",
                        "refunds");

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

    @Test
    @DisplayName("local/test profile exposes the documented Payment API without weakening business auth")
    void openApiContractIsReachableAndContainsEveryPublicOperation() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/v3/api-docs"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"/api/v1/payments\"")
                .contains("\"/api/v1/payments/{paymentId}\"")
                .contains("\"/api/v1/payments/{paymentId}/refunds\"")
                .contains("\"operationId\":\"createPayment\"")
                .contains("\"operationId\":\"getPayment\"")
                .contains("\"operationId\":\"createRefund\"")
                .contains("202 là đã nhận xử lý, không phải thanh toán đã thành công");
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
