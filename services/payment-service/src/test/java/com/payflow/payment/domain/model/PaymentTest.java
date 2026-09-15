package com.payflow.payment.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

import com.payflow.payment.domain.exception.IllegalStatusTransitionException;
import com.payflow.payment.domain.exception.MerchantNotAcceptingPaymentsException;
import com.payflow.payment.domain.exception.PaymentLimitExceededException;
import com.payflow.payment.domain.exception.UnexpectedPaymentStatusException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentTest {

    private static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PAYMENT_ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final Instant CREATED_AT = Instant.parse("2026-07-24T03:00:00Z");
    private static final Instant LATER = Instant.parse("2026-07-24T03:00:05Z");

    private static MerchantSnapshot merchant(MerchantStatus status, String limit) {
        return MerchantSnapshot.legacyNoFee(
                MERCHANT_ID, status, "VND", Money.of(limit, "VND"));
    }

    private static MerchantSnapshot activeMerchant() {
        return merchant(MerchantStatus.ACTIVE, "50000000");
    }

    private static PaymentIntake intake(String amount) {
        return new PaymentIntake(
                PAYMENT_ID,
                UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95"),
                UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3"),
                "ORDER-2026-00001",
                "d290f1ee-6c54-4b01-90e6-d701748f0851",
                Money.of(amount, "VND"),
                "Thanh toán đơn hàng",
                Map.of("orderId", "ORDER-2026-00001"),
                CREATED_AT);
    }

    @Test
    @DisplayName("a created payment starts in CREATED and carries the intake unchanged")
    void createsInCreatedStatus() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        assertThat(payment.id()).isEqualTo(PAYMENT_ID);
        assertThat(payment.merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.amount()).isEqualTo(Money.of("500000", "VND"));
        assertThat(payment.merchantReference()).isEqualTo("ORDER-2026-00001");
        assertThat(payment.createdAt()).isEqualTo(CREATED_AT);
        assertThat(payment.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(payment.isTerminal()).isFalse();
    }

    /**
     * The history entry is created by the aggregate, not by the caller. If it were the caller's job,
     * every future transition would be one forgotten line away from an incomplete audit trail.
     */
    @Test
    @DisplayName("creation records an initial history entry with no previous status")
    void recordsInitialHistory() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        assertThat(payment.recordedStatusChanges()).hasSize(1);

        PaymentStatusChange change = payment.recordedStatusChanges().getFirst();
        assertThat(change.isInitial()).isTrue();
        assertThat(change.from()).isNull();
        assertThat(change.to()).isEqualTo(PaymentStatus.CREATED);
        assertThat(change.occurredAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest
    @EnumSource(value = MerchantStatus.class, names = "ACTIVE", mode = EXCLUDE)
    @DisplayName("a merchant that is not ACTIVE cannot take a payment")
    void rejectsMerchantThatCannotTransact(MerchantStatus status) {
        MerchantSnapshot blocked = merchant(status, "50000000");

        assertThatExceptionOfType(MerchantNotAcceptingPaymentsException.class)
                .isThrownBy(() -> Payment.create(blocked, intake("500000")))
                .satisfies(
                        e -> {
                            assertThat(e.merchantId()).isEqualTo(MERCHANT_ID);
                            assertThat(e.status()).isEqualTo(status);
                        });
    }

    @Test
    @DisplayName("an amount exactly at the limit is accepted")
    void acceptsAmountAtTheLimit() {
        Payment payment = Payment.create(merchant(MerchantStatus.ACTIVE, "500000"), intake("500000"));

        assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    @DisplayName("one unit above the limit is refused, and the exception carries both amounts")
    void rejectsAmountAboveTheLimit() {
        MerchantSnapshot limited = merchant(MerchantStatus.ACTIVE, "500000");

        assertThatExceptionOfType(PaymentLimitExceededException.class)
                .isThrownBy(() -> Payment.create(limited, intake("500000.0001")))
                .satisfies(
                        e -> {
                            assertThat(e.requested()).isEqualTo(Money.of("500000.0001", "VND"));
                            assertThat(e.limit()).isEqualTo(Money.of("500000", "VND"));
                        });
    }

    @Test
    @DisplayName("a legal transition moves the status, stamps updatedAt, and appends history")
    void legalTransitionIsRecorded() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        payment.transitionTo(PaymentStatus.RISK_CHECKING, "RISK_SUBMITTED", LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.RISK_CHECKING);
        assertThat(payment.updatedAt()).isEqualTo(LATER);
        assertThat(payment.createdAt()).isEqualTo(CREATED_AT);
        assertThat(payment.recordedStatusChanges()).hasSize(2);

        PaymentStatusChange change = payment.recordedStatusChanges().getLast();
        assertThat(change.from()).isEqualTo(PaymentStatus.CREATED);
        assertThat(change.to()).isEqualTo(PaymentStatus.RISK_CHECKING);
        assertThat(change.reasonCode()).isEqualTo("RISK_SUBMITTED");
        assertThat(change.occurredAt()).isEqualTo(LATER);
    }

    /**
     * A rejected transition must change nothing. Under at-least-once delivery a redelivered event hits
     * this path routinely, and an aggregate that recorded a failed attempt would fill the audit trail
     * with movement that never happened.
     */
    @Test
    @DisplayName("a refused transition leaves status, updatedAt, and history untouched")
    void illegalTransitionChangesNothing() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.SUCCEEDED, null, LATER))
                .isInstanceOf(IllegalStatusTransitionException.class);

        assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.updatedAt()).isEqualTo(CREATED_AT);
        assertThat(payment.recordedStatusChanges()).hasSize(1);
    }

    @Test
    @DisplayName("the whole happy path is walkable, and only in order")
    void walksTheHappyPath() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        payment.transitionTo(PaymentStatus.RISK_CHECKING, null, LATER);
        payment.transitionTo(PaymentStatus.RESERVING_FUNDS, null, LATER);
        payment.transitionTo(PaymentStatus.PROCESSING, null, LATER);
        payment.transitionTo(PaymentStatus.SUCCEEDED, null, LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.recordedStatusChanges()).hasSize(5);
        assertThat(payment.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("submitting for risk records the explicit CREATED to RISK_CHECKING edge")
    void submitsForRisk() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        payment.submitForRisk(LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.RISK_CHECKING);
        assertThat(payment.recordedStatusChanges().getLast().reasonCode())
                .isEqualTo("RISK_SUBMITTED");
    }

    @Test
    @DisplayName("merchant cancellation is allowed before, but not after, reservation starts")
    void cancelsOnlyBeforeReservation() {
        Payment checkingRisk = paymentCheckingRisk();

        checkingRisk.cancelBeforeReservation(LATER.plusSeconds(1));

        assertThat(checkingRisk.status()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(checkingRisk.recordedStatusChanges().getLast().reasonCode())
                .isEqualTo("MERCHANT_CANCELLED");

        Payment reserving = paymentCheckingRisk();
        reserving.applyRiskDecision(PaymentRiskDecision.APPROVED, LATER.plusSeconds(1));
        assertThatThrownBy(() -> reserving.cancelBeforeReservation(LATER.plusSeconds(2)))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    @DisplayName("approved risk decision advances to reservation without touching money")
    void approvedRiskRequestsReservation() {
        Payment payment = paymentCheckingRisk();

        PaymentRiskAction action =
                payment.applyRiskDecision(PaymentRiskDecision.APPROVED, LATER.plusSeconds(1));

        assertThat(action).isEqualTo(PaymentRiskAction.REQUEST_FUNDS_RESERVATION);
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
        assertThat(payment.recordedStatusChanges().getLast().reasonCode())
                .isEqualTo("RISK_APPROVED");
    }

    @Test
    @DisplayName("rejected risk decision terminates before reservation")
    void rejectedRiskPublishesFailure() {
        Payment payment = paymentCheckingRisk();

        PaymentRiskAction action =
                payment.applyRiskDecision(PaymentRiskDecision.REJECTED, LATER.plusSeconds(1));

        assertThat(action).isEqualTo(PaymentRiskAction.PUBLISH_PAYMENT_FAILED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.RISK_REJECTED);
        assertThat(payment.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("review-required enters explicit manual review and records why")
    void reviewRequiredEntersManualReview() {
        Payment payment = paymentCheckingRisk();
        int historySize = payment.recordedStatusChanges().size();

        PaymentRiskAction action = payment.applyRiskDecision(
                PaymentRiskDecision.REVIEW_REQUIRED, LATER.plusSeconds(1));

        assertThat(action).isEqualTo(PaymentRiskAction.AWAIT_MANUAL_REVIEW);
        assertThat(payment.status()).isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(payment.recordedStatusChanges()).hasSize(historySize + 1);
        assertThat(payment.recordedStatusChanges().getLast().reasonCode())
                .isEqualTo("RISK_REVIEW_REQUIRED");
    }

    @Test
    @DisplayName("risk result cannot be applied before the payment is checking risk")
    void refusesRiskDecisionInUnexpectedStatus() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        assertThatThrownBy(() -> payment.applyRiskDecision(PaymentRiskDecision.APPROVED, LATER))
                .isInstanceOf(UnexpectedPaymentStatusException.class)
                .satisfies(failure -> {
                    UnexpectedPaymentStatusException statusFailure =
                            (UnexpectedPaymentStatusException) failure;
                    assertThat(statusFailure.expected()).isEqualTo(PaymentStatus.RISK_CHECKING);
                    assertThat(statusFailure.actual()).isEqualTo(PaymentStatus.CREATED);
                });
        assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(payment.recordedStatusChanges()).hasSize(1);
    }

    @Test
    @DisplayName("a terminal payment refuses every further move")
    void terminalPaymentIsFrozen() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));
        payment.transitionTo(PaymentStatus.CANCELLED, "MERCHANT_CANCELLED", LATER);

        assertThat(payment.isTerminal()).isTrue();

        for (PaymentStatus target : PaymentStatus.values()) {
            assertThatThrownBy(() -> payment.transitionTo(target, null, LATER))
                    .as("cancelled payment must refuse %s", target)
                    .isInstanceOf(IllegalStatusTransitionException.class);
        }
    }

    @Test
    @DisplayName("a rehydrated payment has no recorded changes to write")
    void rehydrationRecordsNothing() {
        Payment payment =
                Payment.rehydrateLegacyNoFee(
                        PAYMENT_ID, MERCHANT_ID, intake("500000"), PaymentStatus.SUCCEEDED, LATER);

        assertThat(payment.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.updatedAt()).isEqualTo(LATER);
        assertThat(payment.recordedStatusChanges()).isEmpty();
    }

    @Test
    void auditedManualReviewResolutionUsesOnlyExplicitSemanticTransitions() {
        Payment approved = Payment.rehydrateLegacyNoFee(
                PAYMENT_ID, MERCHANT_ID, intake("500000"),
                PaymentStatus.MANUAL_REVIEW_REQUIRED, LATER);
        approved.approveRiskManualReview(LATER.plusSeconds(1));

        Payment retried = Payment.rehydrateLegacyNoFee(
                PAYMENT_ID, MERCHANT_ID, intake("500000"),
                PaymentStatus.MANUAL_REVIEW_REQUIRED, LATER);
        retried.resumeProcessingAfterManualReview(LATER.plusSeconds(1));

        assertThat(approved.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
        assertThat(retried.status()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(approved.recordedStatusChanges().getFirst().reasonCode())
                .isEqualTo("MANUAL_REVIEW_RISK_APPROVED");
        assertThat(retried.recordedStatusChanges().getFirst().reasonCode())
                .isEqualTo("MANUAL_REVIEW_RETRY");
    }

    /**
     * Rehydration must not re-apply acceptance rules. A merchant suspended after the fact would
     * otherwise make its own historical payments unreadable, precisely when someone needs to read them.
     */
    @Test
    @DisplayName("rehydration accepts a payment its merchant could no longer make")
    void rehydrationDoesNotRevalidate() {
        Payment payment =
                Payment.rehydrateLegacyNoFee(
                        PAYMENT_ID,
                        MERCHANT_ID,
                        intake("999999999"),
                        PaymentStatus.SUCCEEDED,
                        LATER);

        assertThat(payment.amount()).isEqualTo(Money.of("999999999", "VND"));
    }

    @Test
    @DisplayName("two instances with the same id are the same payment")
    void identityIsTheId() {
        Payment created = Payment.create(activeMerchant(), intake("500000"));
        Payment loaded =
                Payment.rehydrateLegacyNoFee(
                        PAYMENT_ID, MERCHANT_ID, intake("500000"), PaymentStatus.SUCCEEDED, LATER);

        assertThat(created).isEqualTo(loaded).hasSameHashCodeAs(loaded);
    }

    @Test
    @DisplayName("the recorded-changes list cannot be modified through its accessor")
    void recordedChangesAreACopy() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        assertThatThrownBy(() -> payment.recordedStatusChanges().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(payment.recordedStatusChanges()).hasSize(1);
    }

    @Test
    @DisplayName("metadata cannot be changed after the intake was validated")
    void metadataIsImmutable() {
        Map<String, String> mutable = new HashMap<>();
        mutable.put("orderId", "ORDER-1");

        PaymentIntake intake =
                new PaymentIntake(
                        PAYMENT_ID,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ORDER-1",
                        "key-1",
                        Money.of("100", "VND"),
                        null,
                        mutable,
                        CREATED_AT);

        mutable.put("injected", "after validation");

        Payment payment = Payment.create(activeMerchant(), intake);

        assertThat(payment.metadata()).containsExactly(Map.entry("orderId", "ORDER-1"));
        assertThatThrownBy(() -> payment.metadata().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("toString carries no merchant-supplied text")
    void toStringOmitsMerchantContent() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));

        assertThat(payment).hasToString(
                "Payment[id=" + PAYMENT_ID + ", merchantId=" + MERCHANT_ID
                        + ", amount=500000.0000 VND, status=CREATED]");
        assertThat(payment.toString()).doesNotContain("Thanh toán", "ORDER-2026-00001");
    }

    private static Payment paymentCheckingRisk() {
        Payment payment = Payment.create(activeMerchant(), intake("500000"));
        payment.submitForRisk(LATER);
        return payment;
    }
}
