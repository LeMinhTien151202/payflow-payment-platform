package com.payflow.payment.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsCapturedData;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentManualReviewRequiredData;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.events.risk.RiskEvents;
import com.payflow.events.risk.RiskLevelValue;
import com.payflow.payment.application.exception.PaymentSagaContractMismatchException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSagaEventFactoryTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID RESERVATION_ID =
            UUID.fromString("41734b31-8e75-4570-bdc6-979fa02ab447");
    private static final UUID MERCHANT_ID =
            UUID.fromString("2f1c7a30-0b5e-4a4e-9a94-6a1a1cbb1111");
    private static final UUID CUSTOMER_ID =
            UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95");
    private static final BigDecimal AMOUNT = new BigDecimal("500000");
    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
    private static final String CORRELATION_ID = "correlation-1";

    private final PaymentSagaEventFactory factory = new PaymentSagaEventFactory();

    @Test
    void buildsEveryHappyPathEnvelopeWithPaymentKeyAndDirectCausation() {
        var reserve = factory.reserveRequested(
                UUID.randomUUID(),
                risk(RiskDecisionValue.APPROVED),
                new AccountReserveRequestedData(
                        PAYMENT_ID, ACCOUNT_ID, AMOUNT, "VND", NOW.plusSeconds(900)),
                NOW.plusSeconds(1));
        var ledger = factory.ledgerPostRequested(
                UUID.randomUUID(),
                reserved(),
                new LedgerPostPaymentRequestedData(
                        PAYMENT_ID, CUSTOMER_ID, MERCHANT_ID, AMOUNT, "VND"),
                NOW.plusSeconds(3));
        var capture = factory.captureRequested(
                UUID.randomUUID(),
                ledgerPosted(),
                new AccountCaptureRequestedData(
                        PAYMENT_ID, ACCOUNT_ID, RESERVATION_ID, AMOUNT, "VND"),
                NOW.plusSeconds(5));
        var succeeded = factory.paymentSucceeded(
                UUID.randomUUID(),
                captured(),
                new PaymentSucceededData(
                        PAYMENT_ID,
                        MERCHANT_ID,
                        CUSTOMER_ID,
                        AMOUNT,
                        "VND",
                        NOW.plusSeconds(7)),
                NOW.plusSeconds(7));

        assertEnvelope(reserve, "account.reserve.requested", risk(RiskDecisionValue.APPROVED).eventId());
        assertEnvelope(ledger, "ledger.post-payment.requested", reserved().eventId());
        assertEnvelope(capture, "account.capture.requested", ledgerPosted().eventId());
        assertEnvelope(succeeded, "payment.succeeded", captured().eventId());
    }

    @Test
    void buildsReservationFailureWithSameStableCode() {
        EventEnvelope<AccountFundsReservationFailedData> cause = reservationFailed();

        var outcome = factory.reservationFailed(
                UUID.randomUUID(),
                cause,
                new PaymentFailedData(
                        PAYMENT_ID, "ACCOUNT_INSUFFICIENT_FUNDS", NOW.plusSeconds(3)),
                NOW.plusSeconds(3));

        assertEnvelope(outcome, "payment.failed", cause.eventId());
        assertThat(outcome.data().failureCode()).isEqualTo(cause.data().reasonCode());
    }

    @Test
    void nonApprovedRiskCannotCreateReserveCommand() {
        var data = new AccountReserveRequestedData(
                PAYMENT_ID, ACCOUNT_ID, AMOUNT, "VND", NOW.plusSeconds(900));

        assertThatThrownBy(() -> factory.reserveRequested(
                        UUID.randomUUID(),
                        risk(RiskDecisionValue.REJECTED),
                        data,
                        NOW.plusSeconds(1)))
                .isInstanceOf(PaymentSagaContractMismatchException.class)
                .hasMessageContaining("risk decision");
    }

    @Test
    void wrongCauseContractOrMoneyCannotContinueChain() {
        var wrongCause = EventEnvelope.of(
                UUID.randomUUID(),
                new EventType("account.other", 1, "PAYMENT"),
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "account-ledger-service",
                NOW,
                reserved().data());
        var ledger = new LedgerPostPaymentRequestedData(
                PAYMENT_ID, CUSTOMER_ID, MERCHANT_ID, AMOUNT, "VND");
        var wrongAmount = new LedgerPostPaymentRequestedData(
                PAYMENT_ID,
                CUSTOMER_ID,
                MERCHANT_ID,
                new BigDecimal("499999"),
                "VND");

        assertThatThrownBy(() -> factory.ledgerPostRequested(
                        UUID.randomUUID(), wrongCause, ledger, NOW.plusSeconds(1)))
                .isInstanceOf(PaymentSagaContractMismatchException.class)
                .hasMessageContaining("cause contract");
        assertThatThrownBy(() -> factory.ledgerPostRequested(
                        UUID.randomUUID(), reserved(), wrongAmount, NOW.plusSeconds(1)))
                .isInstanceOf(PaymentSagaContractMismatchException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void logicalCausationDoesNotDependOnCrossServiceWallClockOrdering() {
        var data = new LedgerPostPaymentRequestedData(
                PAYMENT_ID, CUSTOMER_ID, MERCHANT_ID, AMOUNT, "VND");

        var event = factory.ledgerPostRequested(
                UUID.randomUUID(), reserved(), data, NOW.minusSeconds(30));

        assertThat(event.occurredAt()).isEqualTo(NOW.minusSeconds(30));
        assertThat(event.causationId()).isEqualTo(reserved().eventId().toString());
    }

    @Test
    void buildsReleaseAndCompensatedFailureChain() {
        EventEnvelope<LedgerPaymentPostingFailedData> failed = ledgerFailed();
        var release = factory.releaseRequested(
                UUID.randomUUID(),
                failed,
                new AccountReleaseRequestedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        AMOUNT,
                        "VND",
                        "LEDGER_JOURNAL_REJECTED"),
                NOW.plusSeconds(6));
        var paymentFailed = factory.compensationCompleted(
                UUID.randomUUID(),
                fundsReleased(),
                new PaymentFailedData(
                        PAYMENT_ID, "LEDGER_POSTING_FAILED", NOW.plusSeconds(8)),
                NOW.plusSeconds(8));

        assertEnvelope(release, "account.release.requested", failed.eventId());
        assertEnvelope(paymentFailed, "payment.failed", fundsReleased().eventId());
    }

    @Test
    void buildsManualReviewForSamePaymentCause() {
        var event = factory.manualReviewRequired(
                UUID.randomUUID(),
                ledgerPosted(),
                new PaymentManualReviewRequiredData(
                        PAYMENT_ID, "CAPTURE_FUNDS", "CAPTURE_RETRY_EXHAUSTED"),
                NOW.plusSeconds(8));

        assertEnvelope(event, "payment.manual-review-required", ledgerPosted().eventId());
    }

    private static void assertEnvelope(
            EventEnvelope<?> envelope, String eventType, UUID causationId) {
        assertThat(envelope.eventType()).isEqualTo(eventType);
        assertThat(envelope.eventVersion()).isEqualTo(1);
        assertThat(envelope.aggregateType()).isEqualTo("PAYMENT");
        assertThat(envelope.aggregateId()).isEqualTo(PAYMENT_ID.toString());
        assertThat(envelope.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(envelope.causationId()).isEqualTo(causationId.toString());
        assertThat(envelope.producer()).isEqualTo("payment-service");
    }

    private static EventEnvelope<RiskAssessmentCompletedData> risk(RiskDecisionValue decision) {
        return EventEnvelope.of(
                UUID.fromString("11734b31-8e75-4570-bdc6-979fa02ab441"),
                RiskEvents.RISK_ASSESSMENT_COMPLETED,
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "risk-service",
                NOW,
                new RiskAssessmentCompletedData(
                        PAYMENT_ID, decision, 0, RiskLevelValue.LOW, List.of(), "risk-v1"));
    }

    private static EventEnvelope<AccountFundsReservedData> reserved() {
        return EventEnvelope.of(
                UUID.fromString("21734b31-8e75-4570-bdc6-979fa02ab442"),
                AccountEvents.FUNDS_RESERVED,
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "account-ledger-service",
                NOW.plusSeconds(2),
                new AccountFundsReservedData(
                        PAYMENT_ID, ACCOUNT_ID, RESERVATION_ID, AMOUNT, "VND"));
    }

    private static EventEnvelope<AccountFundsReservationFailedData> reservationFailed() {
        return EventEnvelope.of(
                UUID.fromString("31734b31-8e75-4570-bdc6-979fa02ab443"),
                AccountEvents.FUNDS_RESERVATION_FAILED,
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "account-ledger-service",
                NOW.plusSeconds(2),
                new AccountFundsReservationFailedData(
                        PAYMENT_ID, ACCOUNT_ID, "ACCOUNT_INSUFFICIENT_FUNDS"));
    }

    private static EventEnvelope<LedgerPaymentPostedData> ledgerPosted() {
        return EventEnvelope.of(
                UUID.fromString("41734b31-8e75-4570-bdc6-979fa02ab444"),
                LedgerEvents.PAYMENT_POSTED,
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "account-ledger-service",
                NOW.plusSeconds(4),
                new LedgerPaymentPostedData(
                        PAYMENT_ID, UUID.randomUUID(), AMOUNT, "VND"));
    }

    private static EventEnvelope<AccountFundsCapturedData> captured() {
        return EventEnvelope.of(
                UUID.fromString("51734b31-8e75-4570-bdc6-979fa02ab445"),
                AccountEvents.FUNDS_CAPTURED,
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "account-ledger-service",
                NOW.plusSeconds(6),
                new AccountFundsCapturedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        AMOUNT,
                        "VND",
                        NOW.plusSeconds(6)));
    }

    private static EventEnvelope<LedgerPaymentPostingFailedData> ledgerFailed() {
        return EventEnvelope.of(
                UUID.fromString("61734b31-8e75-4570-bdc6-979fa02ab446"),
                LedgerEvents.PAYMENT_POSTING_FAILED,
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "account-ledger-service",
                NOW.plusSeconds(5),
                new LedgerPaymentPostingFailedData(
                        PAYMENT_ID, "LEDGER_JOURNAL_REJECTED", NOW.plusSeconds(5)));
    }

    private static EventEnvelope<AccountFundsReleasedData> fundsReleased() {
        return EventEnvelope.of(
                UUID.fromString("71734b31-8e75-4570-bdc6-979fa02ab447"),
                AccountEvents.FUNDS_RELEASED,
                PAYMENT_ID.toString(),
                CORRELATION_ID,
                "account-ledger-service",
                NOW.plusSeconds(7),
                new AccountFundsReleasedData(
                        PAYMENT_ID,
                        ACCOUNT_ID,
                        RESERVATION_ID,
                        AMOUNT,
                        "VND",
                        "LEDGER_JOURNAL_REJECTED",
                        NOW.plusSeconds(7)));
    }
}
