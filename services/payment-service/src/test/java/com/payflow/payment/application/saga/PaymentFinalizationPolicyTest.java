package com.payflow.payment.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.account.AccountCaptureRequestedData;
import com.payflow.events.account.AccountFundsCapturedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.ledger.LedgerPaymentPostedData;
import com.payflow.events.payment.PaymentSucceededData;
import com.payflow.payment.application.exception.FinancialFinalizationMismatchException;
import com.payflow.payment.domain.exception.UnexpectedPaymentStatusException;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentFinalizationPolicyTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID MERCHANT_ID =
            UUID.fromString("2f1c7a30-0b5e-4a4e-9a94-6a1a1cbb1111");
    private static final UUID CUSTOMER_ID =
            UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID RESERVATION_ID =
            UUID.fromString("41734b31-8e75-4570-bdc6-979fa02ab447");
    private static final UUID JOURNAL_ID =
            UUID.fromString("51734b31-8e75-4570-bdc6-979fa02ab447");
    private static final Instant CREATED_AT = Instant.parse("2026-07-28T09:00:00Z");
    private static final Instant CAPTURED_AT = Instant.parse("2026-07-28T09:00:03Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-07-28T09:00:04Z");

    private final PaymentFinalizationPolicy policy = new PaymentFinalizationPolicy();

    @Test
    void ledgerAcknowledgementCreatesExplicitCaptureCommandWithoutPublishingSuccess() {
        Payment payment = processingPayment();

        AccountCaptureRequestedData command =
                policy.requestCapture(payment, reservation(), ledger());

        assertThat(command).isEqualTo(new AccountCaptureRequestedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                new BigDecimal("500000.0000"),
                "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.recordedStatusChanges()).isEmpty();
    }

    @Test
    void completesOnlyAfterMatchingLedgerAndCaptureFacts() {
        Payment payment = processingPayment();

        PaymentSucceededData outcome =
                policy.complete(payment, reservation(), ledger(), capture(RESERVATION_ID), PROCESSED_AT);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.updatedAt()).isEqualTo(PROCESSED_AT);
        assertThat(payment.recordedStatusChanges()).singleElement().satisfies(change -> {
            assertThat(change.from()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(change.to()).isEqualTo(PaymentStatus.SUCCEEDED);
            assertThat(change.reasonCode()).isEqualTo("FINANCIAL_FINALIZATION_CONFIRMED");
        });
        assertThat(outcome).isEqualTo(new PaymentSucceededData(
                PAYMENT_ID,
                MERCHANT_ID,
                CUSTOMER_ID,
                new BigDecimal("500000.0000"),
                "VND",
                PROCESSED_AT));
    }

    @Test
    void producerTimestampCannotMovePaymentClock() {
        Payment payment = processingPayment();
        AccountFundsCapturedData oldProducerClock = new AccountFundsCapturedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                new BigDecimal("500000"),
                "VND",
                Instant.parse("2020-01-01T00:00:00Z"));

        policy.complete(payment, reservation(), ledger(), oldProducerClock, PROCESSED_AT);

        assertThat(payment.updatedAt()).isEqualTo(PROCESSED_AT);
    }

    @Test
    void rejectsLedgerAmountMismatchBeforeRequestingCapture() {
        Payment payment = processingPayment();
        var wrongLedger = new LedgerPaymentPostedData(
                PAYMENT_ID, JOURNAL_ID, new BigDecimal("499999"), "VND");

        assertThatThrownBy(() -> policy.requestCapture(payment, reservation(), wrongLedger))
                .isInstanceOf(FinancialFinalizationMismatchException.class)
                .hasMessageContaining("ledger amount");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void rejectsCaptureForAnotherReservationBeforeSuccess() {
        Payment payment = processingPayment();

        assertThatThrownBy(() -> policy.complete(
                        payment, reservation(), ledger(), capture(UUID.randomUUID()), PROCESSED_AT))
                .isInstanceOf(FinancialFinalizationMismatchException.class)
                .hasMessageContaining("reservationId");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.recordedStatusChanges()).isEmpty();
    }

    @Test
    void rejectsCrossPaymentFactsBeforeSuccess() {
        Payment payment = processingPayment();
        var otherLedger = new LedgerPaymentPostedData(
                UUID.randomUUID(), JOURNAL_ID, new BigDecimal("500000"), "VND");

        assertThatThrownBy(() -> policy.complete(
                        payment, reservation(), otherLedger, capture(RESERVATION_ID), PROCESSED_AT))
                .isInstanceOf(FinancialFinalizationMismatchException.class)
                .hasMessageContaining("paymentId");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    @Test
    void rejectsFinalizationOutsideProcessingState() {
        Payment payment = paymentAt(PaymentStatus.RESERVING_FUNDS);

        assertThatThrownBy(() -> policy.requestCapture(payment, reservation(), ledger()))
                .isInstanceOf(UnexpectedPaymentStatusException.class)
                .hasMessageContaining("must be PROCESSING");
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
    }

    @Test
    void missingCaptureCannotCompletePayment() {
        Payment payment = processingPayment();

        assertThatThrownBy(
                        () -> policy.complete(payment, reservation(), ledger(), null, PROCESSED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("capture");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    private static AccountFundsReservedData reservation() {
        return new AccountFundsReservedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                new BigDecimal("500000"),
                "VND");
    }

    private static LedgerPaymentPostedData ledger() {
        return new LedgerPaymentPostedData(
                PAYMENT_ID, JOURNAL_ID, new BigDecimal("500000"), "VND");
    }

    private static AccountFundsCapturedData capture(UUID reservationId) {
        return new AccountFundsCapturedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                reservationId,
                new BigDecimal("500000"),
                "VND",
                CAPTURED_AT);
    }

    private static Payment processingPayment() {
        return paymentAt(PaymentStatus.PROCESSING);
    }

    private static Payment paymentAt(PaymentStatus status) {
        PaymentIntake intake = new PaymentIntake(
                PAYMENT_ID,
                CUSTOMER_ID,
                ACCOUNT_ID,
                "ORDER-1",
                "idempotency-key",
                Money.of("500000", "VND"),
                null,
                Map.of(),
                CREATED_AT);
        return Payment.rehydrate(PAYMENT_ID, MERCHANT_ID, intake, status, CREATED_AT);
    }
}
