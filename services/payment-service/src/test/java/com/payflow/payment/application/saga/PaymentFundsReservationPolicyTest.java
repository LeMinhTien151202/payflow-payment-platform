package com.payflow.payment.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.account.AccountFundsReservationFailedData;
import com.payflow.events.account.AccountFundsReservedData;
import com.payflow.events.account.AccountReserveRequestedData;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import com.payflow.events.payment.PaymentFailedData;
import com.payflow.payment.application.exception.FundsReservationMismatchException;
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

class PaymentFundsReservationPolicyTest {

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
    private static final Instant CREATED_AT = Instant.parse("2026-07-28T10:00:00Z");
    private static final Instant PROCESSED_AT = CREATED_AT.plusSeconds(2);

    private final PaymentFundsReservationPolicy policy =
            new PaymentFundsReservationPolicy();

    @Test
    void createsReserveCommandFromPaymentOwnedFacts() {
        Payment payment = reservingPayment();

        AccountReserveRequestedData command =
                policy.request(payment, CREATED_AT.plusSeconds(1), CREATED_AT.plusSeconds(901));

        assertThat(command.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(command.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(command.amount()).isEqualTo(new BigDecimal("500000.0000"));
        assertThat(command.currency()).isEqualTo("VND");
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
    }

    @Test
    void rejectsNonFutureReservationDeadline() {
        Payment payment = reservingPayment();

        assertThatThrownBy(() -> policy.request(payment, PROCESSED_AT, PROCESSED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiry");
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
    }

    @Test
    void matchingReservedFactStartsLedgerProcessing() {
        Payment payment = reservingPayment();

        LedgerPostPaymentRequestedData command =
                policy.applyReserved(payment, reserved(), PROCESSED_AT);

        assertThat(command.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(command.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(command.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(command.amount()).isEqualTo(new BigDecimal("500000.0000"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(payment.recordedStatusChanges()).singleElement().satisfies(change -> {
            assertThat(change.from()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
            assertThat(change.to()).isEqualTo(PaymentStatus.PROCESSING);
            assertThat(change.reasonCode()).isEqualTo("FUNDS_RESERVED");
        });
    }

    @Test
    void definitiveReservationFailureTerminatesWithoutLedgerCommand() {
        Payment payment = reservingPayment();
        var failed = new AccountFundsReservationFailedData(
                PAYMENT_ID, ACCOUNT_ID, "ACCOUNT_INSUFFICIENT_FUNDS");

        PaymentFailedData outcome = policy.applyFailed(payment, failed, PROCESSED_AT);

        assertThat(outcome.failureCode()).isEqualTo("ACCOUNT_INSUFFICIENT_FUNDS");
        assertThat(outcome.failedAt()).isEqualTo(PROCESSED_AT);
        assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.recordedStatusChanges()).singleElement().satisfies(change ->
                assertThat(change.reasonCode()).isEqualTo("ACCOUNT_INSUFFICIENT_FUNDS"));
    }

    @Test
    void reservedAmountMismatchDoesNotAdvancePayment() {
        Payment payment = reservingPayment();
        var wrong = new AccountFundsReservedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                new BigDecimal("499999"),
                "VND");

        assertThatThrownBy(() -> policy.applyReserved(payment, wrong, PROCESSED_AT))
                .isInstanceOf(FundsReservationMismatchException.class)
                .hasMessageContaining("amount");
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
        assertThat(payment.recordedStatusChanges()).isEmpty();
    }

    @Test
    void failureForAnotherAccountDoesNotTerminatePayment() {
        Payment payment = reservingPayment();
        var wrong = new AccountFundsReservationFailedData(
                PAYMENT_ID, UUID.randomUUID(), "ACCOUNT_INSUFFICIENT_FUNDS");

        assertThatThrownBy(() -> policy.applyFailed(payment, wrong, PROCESSED_AT))
                .isInstanceOf(FundsReservationMismatchException.class)
                .hasMessageContaining("accountId");
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
    }

    @Test
    void staleReservationResultCannotRegressProcessingPayment() {
        Payment payment = paymentAt(PaymentStatus.PROCESSING);

        assertThatThrownBy(() -> policy.applyReserved(payment, reserved(), PROCESSED_AT))
                .isInstanceOf(UnexpectedPaymentStatusException.class)
                .hasMessageContaining("RESERVING_FUNDS");
        assertThat(payment.status()).isEqualTo(PaymentStatus.PROCESSING);
    }

    private static AccountFundsReservedData reserved() {
        return new AccountFundsReservedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                RESERVATION_ID,
                new BigDecimal("500000"),
                "VND");
    }

    private static Payment reservingPayment() {
        return paymentAt(PaymentStatus.RESERVING_FUNDS);
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
