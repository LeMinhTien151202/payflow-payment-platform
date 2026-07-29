package com.payflow.accountledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.accountledger.account.application.handler.HandleCaptureFundsRequestedHandler;
import com.payflow.accountledger.account.application.handler.HandleReleaseFundsRequestedHandler;
import com.payflow.accountledger.account.application.handler.HandleReserveFundsRequestedHandler;
import com.payflow.accountledger.application.inbox.EventProcessingResult;
import com.payflow.accountledger.ledger.application.handler.HandlePostPaymentRequestedHandler;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL evidence prepared for reserve -> journal -> capture and pre-ledger compensation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentWorkflowPersistenceIT extends AbstractPostgresIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HandleReserveFundsRequestedHandler reserveHandler;
    @Autowired private HandlePostPaymentRequestedHandler ledgerHandler;
    @Autowired private HandleCaptureFundsRequestedHandler captureHandler;
    @Autowired private HandleReleaseFundsRequestedHandler releaseHandler;

    @Test
    void reservesPostsBalancedJournalAndCapturesExactlyOnceAcrossRedelivery() {
        Fixture fixture = seed("100.0000");
        var reserve = reserveRequested(fixture);

        assertThat(reserveHandler.handle(reserve)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(reserveHandler.handle(reserve)).isEqualTo(EventProcessingResult.DUPLICATE);
        UUID reservationId = jdbc.queryForObject(
                "SELECT id FROM account.balance_reservations WHERE payment_id = ?",
                UUID.class,
                fixture.paymentId());

        var post = postPaymentRequested(fixture);
        assertThat(ledgerHandler.handle(post)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(ledgerHandler.handle(post)).isEqualTo(EventProcessingResult.DUPLICATE);
        UUID journalId = jdbc.queryForObject(
                "SELECT journal_id FROM ledger.payment_postings WHERE payment_id = ?",
                UUID.class,
                fixture.paymentId());

        var capture = captureRequested(fixture, reservationId);
        assertThat(captureHandler.handle(capture)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(captureHandler.handle(capture)).isEqualTo(EventProcessingResult.DUPLICATE);

        assertThat(jdbc.queryForObject(
                        "SELECT available_balance FROM account.accounts WHERE id = ?",
                        BigDecimal.class,
                        fixture.accountId()))
                .isEqualByComparingTo("60.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_balance FROM account.accounts WHERE id = ?",
                        BigDecimal.class,
                        fixture.accountId()))
                .isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM account.balance_reservations WHERE id = ?",
                        String.class,
                        reservationId))
                .isEqualTo("CAPTURED");
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
                        "SELECT count(*) FROM account_ledger.outbox_events"
                                + " WHERE aggregate_id = ? AND event_type IN"
                                + " ('account.funds-reserved', 'ledger.payment-posted',"
                                + "  'account.funds-captured')",
                        Integer.class,
                        fixture.paymentId().toString()))
                .isEqualTo(3);
    }

    @Test
    void reserveOutboxFailureRollsBackInboxBalanceAndReservation() {
        Fixture fixture = seed("100.0000");
        var reserve = reserveRequested(fixture);
        jdbc.execute("""
                CREATE FUNCTION account_ledger.test_reject_reserve_outbox() RETURNS TRIGGER
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_type = 'account.funds-reserved' THEN
                        RAISE EXCEPTION 'injected reserve outbox failure';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER trg_test_reject_reserve_outbox
                BEFORE INSERT ON account_ledger.outbox_events
                FOR EACH ROW EXECUTE FUNCTION account_ledger.test_reject_reserve_outbox()
                """);

        try {
            assertThatThrownBy(() -> reserveHandler.handle(reserve))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER trg_test_reject_reserve_outbox"
                    + " ON account_ledger.outbox_events");
            jdbc.execute("DROP FUNCTION account_ledger.test_reject_reserve_outbox()");
        }

        assertThat(jdbc.queryForObject(
                        "SELECT available_balance FROM account.accounts WHERE id = ?",
                        BigDecimal.class,
                        fixture.accountId()))
                .isEqualByComparingTo("100.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_balance FROM account.accounts WHERE id = ?",
                        BigDecimal.class,
                        fixture.accountId()))
                .isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM account.balance_reservations WHERE payment_id = ?",
                        Integer.class,
                        fixture.paymentId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM account_ledger.processed_events WHERE event_id = ?",
                        Integer.class,
                        reserve.eventId()))
                .isZero();
    }

    @Test
    void preLedgerReleaseReturnsTheReservedAmount() {
        Fixture fixture = seed("100.0000");
        assertThat(reserveHandler.handle(reserveRequested(fixture)))
                .isEqualTo(EventProcessingResult.PROCESSED);
        UUID reservationId = jdbc.queryForObject(
                "SELECT id FROM account.balance_reservations WHERE payment_id = ?",
                UUID.class,
                fixture.paymentId());

        var release = releaseRequested(fixture, reservationId);
        assertThat(releaseHandler.handle(release)).isEqualTo(EventProcessingResult.PROCESSED);
        assertThat(releaseHandler.handle(release)).isEqualTo(EventProcessingResult.DUPLICATE);

        assertThat(jdbc.queryForObject(
                        "SELECT available_balance FROM account.accounts WHERE id = ?",
                        BigDecimal.class,
                        fixture.accountId()))
                .isEqualByComparingTo("100.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT reserved_balance FROM account.accounts WHERE id = ?",
                        BigDecimal.class,
                        fixture.accountId()))
                .isEqualByComparingTo("0.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM account.balance_reservations WHERE id = ?",
                        String.class,
                        reservationId))
                .isEqualTo("RELEASED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.payment_postings WHERE payment_id = ?",
                        Integer.class,
                        fixture.paymentId()))
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
                fixture.customerId());
        return fixture;
    }

    private static EventEnvelope<AccountReserveRequestedData> reserveRequested(Fixture fixture) {
        Instant now = Instant.now();
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.RESERVE_REQUESTED,
                fixture.paymentId().toString(),
                "corr-payment-workflow-it",
                "payment-service",
                now,
                new AccountReserveRequestedData(
                        fixture.paymentId(),
                        fixture.accountId(),
                        new BigDecimal("40.0000"),
                        "VND",
                        now.plusSeconds(300)));
    }

    private static EventEnvelope<LedgerPostPaymentRequestedData> postPaymentRequested(
            Fixture fixture) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                LedgerEvents.POST_PAYMENT_REQUESTED,
                fixture.paymentId().toString(),
                "corr-payment-workflow-it",
                "payment-service",
                Instant.now(),
                new LedgerPostPaymentRequestedData(
                        fixture.paymentId(),
                        fixture.customerId(),
                        fixture.merchantId(),
                        new BigDecimal("40.0000"),
                        "VND"));
    }

    private static EventEnvelope<AccountCaptureRequestedData> captureRequested(
            Fixture fixture, UUID reservationId) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.CAPTURE_REQUESTED,
                fixture.paymentId().toString(),
                "corr-payment-workflow-it",
                "payment-service",
                Instant.now(),
                new AccountCaptureRequestedData(
                        fixture.paymentId(),
                        fixture.accountId(),
                        reservationId,
                        new BigDecimal("40.0000"),
                        "VND"));
    }

    private static EventEnvelope<AccountReleaseRequestedData> releaseRequested(
            Fixture fixture, UUID reservationId) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.RELEASE_REQUESTED,
                fixture.paymentId().toString(),
                "corr-payment-workflow-it",
                "payment-service",
                Instant.now(),
                new AccountReleaseRequestedData(
                        fixture.paymentId(),
                        fixture.accountId(),
                        reservationId,
                        new BigDecimal("40.0000"),
                        "VND",
                        "LEDGER_POSTING_FAILED"));
    }

    private record Fixture(
            UUID paymentId, UUID customerId, UUID merchantId, UUID accountId) {}
}
