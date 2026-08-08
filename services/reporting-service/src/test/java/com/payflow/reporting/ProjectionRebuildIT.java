package com.payflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.reporting.application.ProjectionEventParser;
import com.payflow.reporting.application.port.ProjectionStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves a replay produces the same business projection before atomically switching. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProjectionRebuildIT extends AbstractPostgresIT {

    @Autowired private ProjectionStore store;
    @Autowired private ProjectionEventParser parser;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void rebuildIsEquivalentAndDoesNotDuplicateTheEventLog() {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        append(paymentId, envelope(
                UUID.randomUUID(),
                "payment.created",
                paymentId,
                now,
                "{\"paymentId\":\"" + paymentId + "\",\"merchantId\":\"" + merchantId
                        + "\",\"customerId\":\"" + customerId
                        + "\",\"amount\":\"100.0000\",\"currency\":\"VND\",\"createdAt\":\""
                        + now + "\"}"));
        append(paymentId, envelope(
                UUID.randomUUID(),
                "payment.succeeded",
                paymentId,
                now.plusSeconds(1),
                "{\"paymentId\":\"" + paymentId + "\",\"merchantId\":\"" + merchantId
                        + "\",\"customerId\":\"" + customerId
                        + "\",\"amount\":\"100.0000\",\"currency\":\"VND\",\"completedAt\":\""
                        + now.plusSeconds(1) + "\"}"));
        append(paymentId, envelope(
                UUID.randomUUID(),
                "refund.succeeded",
                paymentId,
                now.plusSeconds(2),
                "{\"refundId\":\"" + UUID.randomUUID() + "\",\"paymentId\":\"" + paymentId
                        + "\",\"amount\":\"25.0000\",\"currency\":\"VND\",\"completedAt\":\""
                        + now.plusSeconds(2) + "\"}"));

        UUID beforeGeneration = store.activeGeneration();
        var before = store.daily(merchantId, now.minusSeconds(1), now.plusSeconds(60));
        UUID afterGeneration = store.rebuild("phase2-it", "corr-reporting-rebuild-it", now.plusSeconds(3));
        var after = store.daily(merchantId, now.minusSeconds(1), now.plusSeconds(60));

        assertThat(afterGeneration).isNotEqualTo(beforeGeneration);
        assertThat(after).isEqualTo(before);
        assertThat(after).singleElement().satisfies(metric -> {
            assertThat(metric.total()).isEqualTo(1);
            assertThat(metric.succeeded()).isEqualTo(1);
            assertThat(metric.gross()).isEqualByComparingTo("100.0000");
            assertThat(metric.refunded()).isEqualByComparingTo("25.0000");
        });
        assertThat(jdbc.queryForObject("select count(*) from reporting.event_log", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "select count(*) from reporting.audit_records"
                                + " where action='REPORTING_REBUILD'",
                        Integer.class))
                .isEqualTo(1);
    }

    private void append(UUID paymentId, String payload) {
        assertThat(store.appendAndProject(parser.parse(paymentId.toString(), payload))).isTrue();
    }

    private static String envelope(
            UUID eventId, String eventType, UUID aggregateId, Instant occurredAt, String data) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + eventType
                + "\",\"eventVersion\":1,\"aggregateId\":\"" + aggregateId
                + "\",\"occurredAt\":\"" + occurredAt + "\",\"data\":" + data + "}";
    }
}
