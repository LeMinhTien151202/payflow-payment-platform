package com.payflow.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import com.payflow.ledger.application.handler.HandleRefundRequestedHandler;
import com.payflow.ledger.application.inbox.EventProcessingResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves refunds append a new balanced journal and posted records cannot be changed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ImmutableRefundJournalIT extends AbstractPostgresIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HandleRefundRequestedHandler handler;

    @Test
    void refundIsExactlyOnceBalancedAndDatabaseImmutable() {
        UUID refundId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        seedLedgerAccount("MERCHANT", merchantId);
        seedLedgerAccount("CUSTOMER_ACCOUNT", accountId);
        var event = requested(refundId, paymentId, merchantId, accountId);

        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(handler.handle(event)).isEqualTo(EventProcessingResult.DUPLICATE);

        UUID journalId = jdbc.queryForObject(
                "select journal_id from ledger.refund_postings where refund_id=?",
                UUID.class,
                refundId);
        assertThat(jdbc.queryForObject(
                        "select count(*) from ledger.entries where journal_id=?",
                        Integer.class,
                        journalId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select sum(case when direction='DEBIT' then amount else -amount end)"
                                + " from ledger.entries where journal_id=?",
                        BigDecimal.class,
                        journalId))
                .isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject(
                        "select count(*) from ledger_runtime.outbox_events"
                                + " where event_type='ledger.refund-posted'",
                        Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                        "update ledger.journals set description='tampered' where id=?", journalId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("posted ledger records are immutable");
    }

    private void seedLedgerAccount(String ownerType, UUID ownerId) {
        jdbc.update(
                "insert into ledger.ledger_accounts(id,owner_type,owner_id,currency)"
                        + " values (?,?,?,'VND')",
                UUID.randomUUID(),
                ownerType,
                ownerId);
    }

    private static EventEnvelope<RefundRequestedData> requested(
            UUID refundId, UUID paymentId, UUID merchantId, UUID accountId) {
        Instant now = Instant.now();
        return EventEnvelope.of(
                UUID.randomUUID(),
                RefundEvents.REFUND_REQUESTED,
                paymentId.toString(),
                "corr-ledger-immutable-it",
                "payment-service",
                now,
                new RefundRequestedData(
                        refundId,
                        paymentId,
                        merchantId,
                        UUID.randomUUID(),
                        accountId,
                        new BigDecimal("40.0000"),
                        "VND",
                        now));
    }
}
