package com.payflow.accountledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.accountledger.account.application.handler.HandleRefundCreditRequestedHandler;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.ledger.application.handler.HandleRefundRequestedHandler;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL transaction evidence prepared for ADR-017/021. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RefundWorkflowPersistenceIT extends AbstractPostgresIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HandleRefundRequestedHandler ledgerHandler;
    @Autowired private HandleRefundCreditRequestedHandler accountHandler;

    @Test
    void postsJournalThenCreditsAccountExactlyOnceAcrossRedelivery() {
        Fixture fixture = seed("100.0000");
        EventEnvelope<RefundRequestedData> refund = refundRequested(fixture);

        assertThat(ledgerHandler.handle(refund)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(ledgerHandler.handle(refund)).isEqualTo(EventProcessingResult.DUPLICATE);

        UUID journalId = jdbc.queryForObject(
                "SELECT journal_id FROM ledger.refund_postings WHERE refund_id = ?",
                UUID.class,
                fixture.refundId());
        EventEnvelope<AccountRefundCreditRequestedData> credit = creditRequested(fixture, journalId);
        assertThat(accountHandler.handle(credit)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(accountHandler.handle(credit)).isEqualTo(EventProcessingResult.DUPLICATE);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.entries WHERE journal_id = ?",
                        Integer.class,
                        journalId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT sum(CASE WHEN direction = 'DEBIT' THEN amount ELSE 0 END)"
                                + " FROM ledger.entries WHERE journal_id = ?",
                        BigDecimal.class,
                        journalId))
                .isEqualByComparingTo("40.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT sum(CASE WHEN direction = 'CREDIT' THEN amount ELSE 0 END)"
                                + " FROM ledger.entries WHERE journal_id = ?",
                        BigDecimal.class,
                        journalId))
                .isEqualByComparingTo("40.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT available_balance FROM account.accounts WHERE id = ?",
                        BigDecimal.class,
                        fixture.accountId()))
                .isEqualByComparingTo("140.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM account.refund_credits WHERE refund_id = ?",
                        Integer.class,
                        fixture.refundId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM account_ledger.outbox_events"
                                + " WHERE aggregate_id = ? AND event_type IN"
                                + " ('ledger.refund-posted', 'account.refund-credited')",
                        Integer.class,
                        fixture.paymentId().toString()))
                .isEqualTo(2);
    }

    @Test
    void accountCreditOutboxFailureRollsBackInboxBalanceAndCredit() {
        Fixture fixture = seed("100.0000");
        UUID eventId = UUID.randomUUID();
        EventEnvelope<AccountRefundCreditRequestedData> credit = EventEnvelope.of(
                eventId,
                AccountEvents.REFUND_CREDIT_REQUESTED,
                fixture.paymentId().toString(),
                "corr-account-ledger-rollback-it",
                "payment-service",
                Instant.now(),
                new AccountRefundCreditRequestedData(
                        fixture.refundId(),
                        fixture.paymentId(),
                        fixture.accountId(),
                        UUID.randomUUID(),
                        new BigDecimal("40.0000"),
                        "VND"));
        jdbc.execute("""
                CREATE FUNCTION account_ledger.test_reject_credit_outbox() RETURNS TRIGGER
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'account.refund-credited' THEN
                        RAISE EXCEPTION 'injected account credit outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_credit_outbox
                BEFORE INSERT ON account_ledger.outbox_events
                FOR EACH ROW EXECUTE FUNCTION account_ledger.test_reject_credit_outbox()
                """);

        try {
            assertThatThrownBy(() -> accountHandler.handle(credit))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_reject_credit_outbox"
                    + " ON account_ledger.outbox_events");
            jdbc.execute("DROP FUNCTION account_ledger.test_reject_credit_outbox()");
        }

        assertThat(jdbc.queryForObject(
                        "SELECT available_balance FROM account.accounts WHERE id = ?",
                        BigDecimal.class,
                        fixture.accountId()))
                .isEqualByComparingTo("100.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM account.refund_credits WHERE refund_id = ?",
                        Integer.class,
                        fixture.refundId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM account_ledger.processed_events WHERE event_id = ?",
                        Integer.class,
                        eventId))
                .isZero();
    }

    private Fixture seed(String balance) {
        Fixture fixture = new Fixture(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update(
                "INSERT INTO account.accounts"
                        + " (id, currency, available_balance, reserved_balance, status)"
                        + " VALUES (?, 'VND', ?, 0, 'ACTIVE')",
                fixture.accountId(),
                new BigDecimal(balance));
        jdbc.update(
                "INSERT INTO ledger.ledger_accounts (id, owner_type, owner_id, currency)"
                        + " VALUES (?, 'MERCHANT', ?, 'VND')",
                UUID.randomUUID(),
                fixture.merchantId());
        jdbc.update(
                "INSERT INTO ledger.ledger_accounts (id, owner_type, owner_id, currency)"
                        + " VALUES (?, 'CUSTOMER_ACCOUNT', ?, 'VND')",
                UUID.randomUUID(),
                fixture.accountId());
        return fixture;
    }

    private static EventEnvelope<RefundRequestedData> refundRequested(Fixture fixture) {
        Instant now = Instant.now();
        return EventEnvelope.of(
                UUID.randomUUID(),
                RefundEvents.REFUND_REQUESTED,
                fixture.paymentId().toString(),
                "corr-account-ledger-it",
                "payment-service",
                now,
                new RefundRequestedData(
                        fixture.refundId(),
                        fixture.paymentId(),
                        fixture.merchantId(),
                        UUID.randomUUID(),
                        fixture.accountId(),
                        new BigDecimal("40.0000"),
                        "VND",
                        now));
    }

    private static EventEnvelope<AccountRefundCreditRequestedData> creditRequested(
            Fixture fixture, UUID journalId) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.REFUND_CREDIT_REQUESTED,
                fixture.paymentId().toString(),
                "corr-account-ledger-it",
                "payment-service",
                Instant.now(),
                new AccountRefundCreditRequestedData(
                        fixture.refundId(),
                        fixture.paymentId(),
                        fixture.accountId(),
                        journalId,
                        new BigDecimal("40.0000"),
                        "VND"));
    }

    private record Fixture(
            UUID refundId, UUID paymentId, UUID merchantId, UUID accountId) {}
}
