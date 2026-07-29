package com.payflow.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.domain.exception.RefundCapacityExceededException;
import com.payflow.payment.domain.exception.RefundNotAllowedException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentRefundCapacityTest {

    private static final UUID PAYMENT_ID = UUID.fromString("9d9dbb29-3f42-43e2-88a5-bc99553f47ba");
    private static final UUID MERCHANT_ID = UUID.fromString("10d30d0c-2905-4e3c-b767-0341ac326254");
    private static final Instant CREATED = Instant.parse("2026-07-29T01:00:00Z");

    @Test
    @DisplayName("in-flight refund consumes capacity before financial success")
    void reservationPreventsOversubscription() {
        Payment payment = succeededPayment("100", "0.02");

        payment.reserveRefund(Money.of("60", "VND"), CREATED.plusSeconds(1));

        assertThat(payment.reservedRefundAmount()).isEqualTo(Money.of("60", "VND"));
        assertThat(payment.totalRefundedAmount()).isEqualTo(Money.zero("VND"));
        assertThat(payment.refundableAmount()).isEqualTo(Money.of("40", "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);

        assertThatExceptionOfType(RefundCapacityExceededException.class)
                .isThrownBy(() -> payment.reserveRefund(Money.of("50", "VND"), CREATED.plusSeconds(2)))
                .satisfies(failure -> assertThat(failure.available()).isEqualTo(Money.of("40", "VND")));
    }

    @Test
    @DisplayName("success moves reserved principal, reverses fee, and changes payment status")
    void successConsumesReservationAndAllocatesFee() {
        Payment payment = succeededPayment("100", "0.02");
        payment.reserveRefund(Money.of("60", "VND"), CREATED.plusSeconds(1));

        Money firstFee = payment.completeRefund(Money.of("60", "VND"), CREATED.plusSeconds(2));

        assertThat(firstFee).isEqualTo(Money.of("1.2", "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(payment.totalRefundedAmount()).isEqualTo(Money.of("60", "VND"));
        assertThat(payment.reservedRefundAmount()).isEqualTo(Money.zero("VND"));

        payment.reserveRefund(Money.of("40", "VND"), CREATED.plusSeconds(3));
        Money finalFee = payment.completeRefund(Money.of("40", "VND"), CREATED.plusSeconds(4));

        assertThat(finalFee).isEqualTo(Money.of("0.8", "VND"));
        assertThat(payment.totalFeeReversedAmount()).isEqualTo(payment.feeSnapshot().feeAmount());
        assertThat(payment.status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.refundableAmount()).isEqualTo(Money.zero("VND"));
    }

    @Test
    @DisplayName("definitive failure releases reserved capacity without claiming a refund")
    void failureReleasesReservation() {
        Payment payment = succeededPayment("100", "0.02");
        payment.reserveRefund(Money.of("70", "VND"), CREATED.plusSeconds(1));

        payment.releaseRefund(Money.of("70", "VND"), CREATED.plusSeconds(2));

        assertThat(payment.reservedRefundAmount()).isEqualTo(Money.zero("VND"));
        assertThat(payment.totalRefundedAmount()).isEqualTo(Money.zero("VND"));
        assertThat(payment.refundableAmount()).isEqualTo(Money.of("100", "VND"));
        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("payment must have succeeded before it can reserve refund capacity")
    void rejectsRefundBeforeSuccess() {
        Payment payment = Payment.create(
                new MerchantSnapshot(
                        MERCHANT_ID,
                        MerchantStatus.ACTIVE,
                        "VND",
                        Money.of("1000", "VND"),
                        policy("0.02")),
                intake("100"));

        assertThatThrownBy(() -> payment.reserveRefund(Money.of("10", "VND"), CREATED.plusSeconds(1)))
                .isInstanceOf(RefundNotAllowedException.class);
    }

    private static Payment succeededPayment(String amount, String rate) {
        PaymentIntake intake = intake(amount);
        PaymentFeeSnapshot fee = PaymentFeeSnapshot.calculate(policy(rate), intake.amount());
        return Payment.rehydrate(
                PAYMENT_ID,
                MERCHANT_ID,
                intake,
                fee,
                PaymentStatus.SUCCEEDED,
                Money.zero("VND"),
                Money.zero("VND"),
                Money.zero("VND"),
                CREATED);
    }

    private static FeePolicySnapshot policy(String rate) {
        return new FeePolicySnapshot("STANDARD_V1", new BigDecimal(rate), RoundingMode.HALF_UP);
    }

    private static PaymentIntake intake(String amount) {
        return new PaymentIntake(
                PAYMENT_ID,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ORDER-REFUND-1",
                "payment-key",
                Money.of(amount, "VND"),
                null,
                Map.of(),
                CREATED);
    }
}
