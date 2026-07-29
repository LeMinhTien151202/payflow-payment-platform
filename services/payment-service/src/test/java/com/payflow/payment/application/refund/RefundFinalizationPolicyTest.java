package com.payflow.payment.application.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.account.AccountRefundCreditedData;
import com.payflow.events.ledger.LedgerRefundPostedData;
import com.payflow.events.ledger.LedgerRefundPostingFailedData;
import com.payflow.payment.application.exception.RefundFinalizationMismatchException;
import com.payflow.payment.domain.model.FeePolicySnapshot;
import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.MerchantStatus;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentFeeSnapshot;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.domain.model.RefundStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefundFinalizationPolicyTest {

    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID REFUND_ID = UUID.fromString("73817fe8-219a-4136-921c-2473c1ea9e9b");
    private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ACCOUNT_ID = UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID JOURNAL_ID = UUID.fromString("3f93e522-42e6-4c3f-9099-9ded706aec77");
    private static final Instant CREATED = Instant.parse("2026-07-29T12:00:00Z");

    private final RefundFinalizationPolicy policy = new RefundFinalizationPolicy();

    @Test
    void ledgerAcknowledgementStartsProcessingAndRequestsOriginalAccountCredit() {
        Fixture fixture = fixture();

        var command = policy.requestCredit(
                fixture.payment(), fixture.refund(), ledger(), CREATED.plusSeconds(2));

        assertThat(fixture.refund().status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(fixture.refund().ledgerJournalId()).isEqualTo(JOURNAL_ID);
        assertThat(fixture.payment().status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(command.refundId()).isEqualTo(REFUND_ID);
        assertThat(command.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(command.journalId()).isEqualTo(JOURNAL_ID);
    }

    @Test
    void matchingCreditCompletesRefundAndConsumesCapacity() {
        Fixture fixture = fixture();
        policy.requestCredit(fixture.payment(), fixture.refund(), ledger(), CREATED.plusSeconds(2));

        var outcome = policy.complete(
                fixture.payment(),
                fixture.refund(),
                ledger(),
                credit("40"),
                CREATED.plusSeconds(3));

        assertThat(fixture.refund().status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(fixture.refund().accountCreditId())
                .isEqualTo(UUID.fromString("e99ff96f-4df3-4f2a-9433-c9aba292786c"));
        assertThat(fixture.payment().status()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(fixture.payment().reservedRefundAmount()).isEqualTo(Money.zero("VND"));
        assertThat(fixture.payment().totalRefundedAmount()).isEqualTo(Money.of("40", "VND"));
        assertThat(outcome.feeReversalAmount()).isEqualByComparingTo("0.8");
        assertThat(outcome.journalId()).isEqualTo(JOURNAL_ID);
    }

    @Test
    void mismatchedCreditCannotFinalizeOrConsumeCapacity() {
        Fixture fixture = fixture();
        policy.requestCredit(fixture.payment(), fixture.refund(), ledger(), CREATED.plusSeconds(2));

        assertThatThrownBy(() -> policy.complete(
                        fixture.payment(),
                        fixture.refund(),
                        ledger(),
                        credit("39"),
                        CREATED.plusSeconds(3)))
                .isInstanceOf(RefundFinalizationMismatchException.class)
                .hasMessageContaining("credit amount");
        assertThat(fixture.refund().status()).isEqualTo(RefundStatus.PROCESSING);
        assertThat(fixture.payment().reservedRefundAmount()).isEqualTo(Money.of("40", "VND"));
    }

    @Test
    void definitivePreJournalFailureReleasesCapacity() {
        Fixture fixture = fixture();
        LedgerRefundPostingFailedData failure = new LedgerRefundPostingFailedData(
                REFUND_ID, PAYMENT_ID, new BigDecimal("40"), "VND", "LEDGER_ACCOUNT_NOT_FOUND");

        var outcome = policy.failBeforeJournal(
                fixture.payment(), fixture.refund(), failure, CREATED.plusSeconds(2));

        assertThat(fixture.refund().status()).isEqualTo(RefundStatus.FAILED);
        assertThat(fixture.payment().reservedRefundAmount()).isEqualTo(Money.zero("VND"));
        assertThat(fixture.payment().refundableAmount()).isEqualTo(Money.of("100", "VND"));
        assertThat(outcome.failureCode()).isEqualTo("LEDGER_ACCOUNT_NOT_FOUND");
    }

    private static Fixture fixture() {
        PaymentIntake intake = new PaymentIntake(
                PAYMENT_ID,
                UUID.randomUUID(),
                ACCOUNT_ID,
                "ORDER-REFUND-FINALIZE",
                "payment-key",
                Money.of("100", "VND"),
                null,
                Map.of(),
                CREATED);
        FeePolicySnapshot policy =
                new FeePolicySnapshot("STANDARD_V1", new BigDecimal("0.02"), RoundingMode.HALF_UP);
        Payment payment = Payment.rehydrate(
                PAYMENT_ID,
                MERCHANT_ID,
                intake,
                PaymentFeeSnapshot.calculate(policy, intake.amount()),
                PaymentStatus.SUCCEEDED,
                Money.zero("VND"),
                Money.zero("VND"),
                Money.zero("VND"),
                CREATED);
        Money amount = Money.of("40", "VND");
        payment.reserveRefund(amount, CREATED.plusSeconds(1));
        Refund refund = Refund.create(
                REFUND_ID,
                PAYMENT_ID,
                MERCHANT_ID,
                "refund-key",
                amount,
                null,
                "merchant-user",
                CREATED.plusSeconds(1));
        return new Fixture(payment, refund);
    }

    private static LedgerRefundPostedData ledger() {
        return new LedgerRefundPostedData(
                REFUND_ID,
                PAYMENT_ID,
                JOURNAL_ID,
                ACCOUNT_ID,
                new BigDecimal("40"),
                "VND");
    }

    private static AccountRefundCreditedData credit(String amount) {
        return new AccountRefundCreditedData(
                REFUND_ID,
                PAYMENT_ID,
                ACCOUNT_ID,
                JOURNAL_ID,
                UUID.fromString("e99ff96f-4df3-4f2a-9433-c9aba292786c"),
                new BigDecimal(amount),
                "VND");
    }

    private record Fixture(Payment payment, Refund refund) {
    }
}
