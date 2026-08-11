package com.payflow.payment.application.saga;

import com.payflow.events.EventEnvelope;
import com.payflow.events.EventType;
import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountFundsCapturedData;
import com.payflow.events.account.AccountFundsReleasedData;
import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReleaseRequestedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerEvents;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.ledger.LedgerPaymentPostingFailedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.events.payment.PaymentManualReviewRequiredData;
import com.payflow.events.payment.PaymentSucceededV2Data;
import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.events.risk.RiskEvents;
import com.payflow.payment.application.exception.PaymentSagaContractMismatchException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Builds Payment-owned Saga envelopes while preserving payment key, correlation and causation. */
public final class PaymentSagaEventFactory {

    public static final String PRODUCER = "payment-service";

    public EventEnvelope<AccountReserveRequestedData> reserveRequested(
            UUID eventId,
            EventEnvelope<RiskAssessmentCompletedData> cause,
            AccountReserveRequestedData data,
            Instant occurredAt) {
        requireCause(cause, RiskEvents.RISK_ASSESSMENT_COMPLETED, data.paymentId());
        if (cause.data().decision() != RiskDecisionValue.APPROVED) {
            throw new PaymentSagaContractMismatchException(
                    "risk decision", RiskDecisionValue.APPROVED, cause.data().decision());
        }
        requireEqual("risk paymentId", cause.data().paymentId(), data.paymentId());
        return causedBy(eventId, AccountEvents.RESERVE_REQUESTED, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<LedgerPostPaymentRequestedData> ledgerPostRequested(
            UUID eventId,
            EventEnvelope<AccountFundsReservedData> cause,
            LedgerPostPaymentRequestedData data,
            Instant occurredAt) {
        requireCause(cause, AccountEvents.FUNDS_RESERVED, data.paymentId());
        requireMoney(cause.data().amount(), cause.data().currency(), data.amount(), data.currency());
        return causedBy(
                eventId, LedgerEvents.POST_PAYMENT_REQUESTED, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<PaymentFailedData> reservationFailed(
            UUID eventId,
            EventEnvelope<AccountFundsReservationFailedData> cause,
            PaymentFailedData data,
            Instant occurredAt) {
        requireCause(cause, AccountEvents.FUNDS_RESERVATION_FAILED, data.paymentId());
        requireEqual("failure code", cause.data().reasonCode(), data.failureCode());
        return causedBy(eventId, PaymentEvents.PAYMENT_FAILED, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<AccountCaptureRequestedData> captureRequested(
            UUID eventId,
            EventEnvelope<LedgerPaymentPostedData> cause,
            AccountCaptureRequestedData data,
            Instant occurredAt) {
        requireCause(cause, LedgerEvents.PAYMENT_POSTED, data.paymentId());
        requireMoney(cause.data().amount(), cause.data().currency(), data.amount(), data.currency());
        return causedBy(eventId, AccountEvents.CAPTURE_REQUESTED, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<PaymentSucceededV2Data> paymentSucceeded(
            UUID eventId,
            EventEnvelope<AccountFundsCapturedData> cause,
            PaymentSucceededV2Data data,
            Instant occurredAt) {
        requireCause(cause, AccountEvents.FUNDS_CAPTURED, data.paymentId());
        requireMoney(cause.data().amount(), cause.data().currency(), data.amount(), data.currency());
        return causedBy(eventId, PaymentEvents.PAYMENT_SUCCEEDED_V2, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<AccountReleaseRequestedData> releaseRequested(
            UUID eventId,
            EventEnvelope<LedgerPaymentPostingFailedData> cause,
            AccountReleaseRequestedData data,
            Instant occurredAt) {
        requireCause(cause, LedgerEvents.PAYMENT_POSTING_FAILED, data.paymentId());
        requireEqual("failure paymentId", cause.data().paymentId(), data.paymentId());
        requireEqual("failure code", cause.data().failureCode(), data.reasonCode());
        return causedBy(
                eventId, AccountEvents.RELEASE_REQUESTED, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<PaymentFailedData> compensationCompleted(
            UUID eventId,
            EventEnvelope<AccountFundsReleasedData> cause,
            PaymentFailedData data,
            Instant occurredAt) {
        requireCause(cause, AccountEvents.FUNDS_RELEASED, data.paymentId());
        return causedBy(
                eventId, PaymentEvents.PAYMENT_FAILED, cause, data.paymentId(), occurredAt, data);
    }

    public EventEnvelope<PaymentManualReviewRequiredData> manualReviewRequired(
            UUID eventId,
            EventEnvelope<?> cause,
            PaymentManualReviewRequiredData data,
            Instant occurredAt) {
        Objects.requireNonNull(cause, "cause");
        requireEqual("cause aggregateId", data.paymentId().toString(), cause.aggregateId());
        return causedBy(
                eventId,
                PaymentEvents.MANUAL_REVIEW_REQUIRED,
                cause,
                data.paymentId(),
                occurredAt,
                data);
    }

    private static void requireCause(
            EventEnvelope<?> cause, EventType expectedType, UUID paymentId) {
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(paymentId, "paymentId");
        boolean validContract = expectedType.name().equals(cause.eventType())
                && expectedType.version() == cause.eventVersion()
                && expectedType.aggregateType().equals(cause.aggregateType());
        if (!validContract) {
            throw new PaymentSagaContractMismatchException(
                    "cause contract",
                    expectedType.name() + " v" + expectedType.version(),
                    cause.eventType() + " v" + cause.eventVersion());
        }
        requireEqual("cause aggregateId", paymentId.toString(), cause.aggregateId());
    }

    private static void requireMoney(
            BigDecimal expectedAmount,
            String expectedCurrency,
            BigDecimal actualAmount,
            String actualCurrency) {
        requireEqual("cause amount", expectedAmount, actualAmount);
        requireEqual("cause currency", expectedCurrency, actualCurrency);
    }

    private static <T> EventEnvelope<T> causedBy(
            UUID eventId,
            EventType type,
            EventEnvelope<?> cause,
            UUID paymentId,
            Instant occurredAt,
            T data) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        return EventEnvelope.causedBy(
                eventId,
                type,
                paymentId.toString(),
                cause,
                PRODUCER,
                occurredAt,
                data);
    }

    private static void requireEqual(String field, Object expected, Object actual) {
        boolean equal = expected instanceof BigDecimal expectedAmount
                && actual instanceof BigDecimal actualAmount
                ? expectedAmount.compareTo(actualAmount) == 0
                : Objects.equals(expected, actual);
        if (!equal) {
            throw new PaymentSagaContractMismatchException(field, expected, actual);
        }
    }
}
