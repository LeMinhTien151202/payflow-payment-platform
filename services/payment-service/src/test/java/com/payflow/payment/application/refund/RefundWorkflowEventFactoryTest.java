package com.payflow.payment.application.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountRefundCreditRequestedData;
import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.ledger.LedgerRefundPostingFailedData;
import com.payflow.events.refund.RefundEvents;
import com.payflow.events.refund.RefundFailedData;
import com.payflow.events.refund.RefundSucceededData;
import com.payflow.payment.application.exception.RefundFinalizationMismatchException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundWorkflowEventFactoryTest {

    private static final UUID REFUND_ID = UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");
    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID JOURNAL_ID = UUID.fromString("3f93e522-42e6-4c3f-9099-9ded706aec77");
    private static final UUID CREDIT_ID = UUID.fromString("e99ff96f-4df3-4f2a-9433-c9aba292786c");
    private static final Instant AT = Instant.parse("2026-07-29T12:00:00Z");

    private final RefundWorkflowEventFactory factory = new RefundWorkflowEventFactory();

    @Test
    void ledgerPostedCausesCreditCommand() {
        EventEnvelope<LedgerRefundPostedData> cause = envelope(
                LedgerEvents.REFUND_POSTED, ledgerPosted(), "account-ledger-service");
        AccountRefundCreditRequestedData data = new AccountRefundCreditRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                JOURNAL_ID,
                new BigDecimal("40"),
                "VND");

        var event = factory.creditRequested(UUID.randomUUID(), cause, data, AT.plusSeconds(1));

        assertThat(event.eventType()).isEqualTo(AccountEvents.REFUND_CREDIT_REQUESTED.name());
        assertThat(event.causationId()).isEqualTo(cause.eventId().toString());
        assertThat(event.aggregateId()).isEqualTo(PAYMENT_ID.toString());
    }

    @Test
    void accountCreditCausesTerminalSuccess() {
        AccountRefundCreditedData credit = credited();
        EventEnvelope<AccountRefundCreditedData> cause = envelope(
                AccountEvents.REFUND_CREDITED, credit, "account-ledger-service");
        RefundSucceededData data = new RefundSucceededData(
                REFUND_ID,
                PAYMENT_ID,
                MERCHANT_ID,
                JOURNAL_ID,
                CREDIT_ID,
                new BigDecimal("40"),
                new BigDecimal("0.8"),
                "VND",
                AT.plusSeconds(1));

        var event = factory.succeeded(UUID.randomUUID(), cause, data, AT.plusSeconds(1));

        assertThat(event.eventType()).isEqualTo(RefundEvents.REFUND_SUCCEEDED.name());
        assertThat(event.causationId()).isEqualTo(cause.eventId().toString());
        assertThat(event.data().feeReversalAmount()).isEqualByComparingTo("0.8");
    }

    @Test
    void ledgerRejectionCausesTerminalFailure() {
        LedgerRefundPostingFailedData rejection = new LedgerRefundPostingFailedData(
                REFUND_ID, PAYMENT_ID, new BigDecimal("40"), "VND", "LEDGER_ACCOUNT_NOT_FOUND");
        EventEnvelope<LedgerRefundPostingFailedData> cause = envelope(
                LedgerEvents.REFUND_POSTING_FAILED, rejection, "account-ledger-service");
        RefundFailedData data = new RefundFailedData(
                REFUND_ID,
                PAYMENT_ID,
                MERCHANT_ID,
                new BigDecimal("40"),
                "VND",
                "LEDGER_ACCOUNT_NOT_FOUND",
                AT.plusSeconds(1));

        var event = factory.failed(UUID.randomUUID(), cause, data, AT.plusSeconds(1));

        assertThat(event.eventType()).isEqualTo(RefundEvents.REFUND_FAILED.name());
        assertThat(event.causationId()).isEqualTo(cause.eventId().toString());
    }

    @Test
    void mismatchedJournalCannotCreateCreditCommand() {
        EventEnvelope<LedgerRefundPostedData> cause = envelope(
                LedgerEvents.REFUND_POSTED, ledgerPosted(), "account-ledger-service");
        AccountRefundCreditRequestedData mismatched = new AccountRefundCreditRequestedData(
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                UUID.randomUUID(),
                new BigDecimal("40"),
                "VND");

        assertThatThrownBy(() -> factory.creditRequested(
                        UUID.randomUUID(), cause, mismatched, AT.plusSeconds(1)))
                .isInstanceOf(RefundFinalizationMismatchException.class)
                .hasMessageContaining("journalId");
    }

    private static LedgerRefundPostedData ledgerPosted() {
        return new LedgerRefundPostedData(
                REFUND_ID,
                PAYMENT_ID,
                JOURNAL_ID,
                ACCOUNT_ID,
                new BigDecimal("40"),
                "VND");
    }

    private static AccountRefundCreditedData credited() {
        return new AccountRefundCreditedData(
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                JOURNAL_ID,
                CREDIT_ID,
                new BigDecimal("40"),
                "VND");
    }

    private static <T> EventEnvelope<T> envelope(
            com.payflow.events.EventType type, T data, String producer) {
        return EventEnvelope.of(
                UUID.randomUUID(),
                type,
                PAYMENT_ID.toString(),
                "corr-refund-workflow",
                producer,
                AT,
                data);
    }
}
