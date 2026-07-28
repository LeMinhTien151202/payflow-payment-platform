package com.payflow.payment.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.risk.RiskAssessmentCompletedData;
import com.payflow.events.risk.RiskDecisionValue;
import com.payflow.events.risk.RiskLevelValue;
import com.payflow.payment.application.exception.RiskAssessmentPaymentMismatchException;
import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.MerchantStatus;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentRiskAction;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ApplyRiskAssessmentPolicyTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID MERCHANT_ID =
            UUID.fromString("2f1c7a30-0b5e-4a4e-9a94-6a1a1cbb1111");
    private static final Instant CREATED_AT = Instant.parse("2026-07-28T08:00:00Z");
    private static final Instant ASSESSED_AT = Instant.parse("2026-07-28T08:00:01Z");

    private final ApplyRiskAssessmentPolicy policy = new ApplyRiskAssessmentPolicy();

    @Test
    void approvedAssessmentRequestsFundsReservation() {
        Payment payment = paymentCheckingRisk();

        assertThat(policy.apply(payment, assessment(RiskDecisionValue.APPROVED), ASSESSED_AT))
                .isEqualTo(PaymentRiskAction.REQUEST_FUNDS_RESERVATION);
        assertThat(payment.status()).isEqualTo(PaymentStatus.RESERVING_FUNDS);
    }

    @Test
    void rejectedAssessmentProducesTerminalFailureAction() {
        Payment payment = paymentCheckingRisk();

        assertThat(policy.apply(payment, assessment(RiskDecisionValue.REJECTED), ASSESSED_AT))
                .isEqualTo(PaymentRiskAction.PUBLISH_PAYMENT_FAILED);
        assertThat(payment.status()).isEqualTo(PaymentStatus.RISK_REJECTED);
        assertThat(ApplyRiskAssessmentPolicy.RISK_REJECTED_FAILURE_CODE)
                .isEqualTo("RISK_REJECTED");
    }

    @Test
    void reviewRequiredWaitsWithoutStartingFinancialWork() {
        Payment payment = paymentCheckingRisk();

        assertThat(policy.apply(
                        payment, assessment(RiskDecisionValue.REVIEW_REQUIRED), ASSESSED_AT))
                .isEqualTo(PaymentRiskAction.AWAIT_MANUAL_REVIEW);
        assertThat(payment.status()).isEqualTo(PaymentStatus.RISK_CHECKING);
    }

    @Test
    void refusesAssessmentForAnotherPaymentBeforeChangingState() {
        Payment payment = paymentCheckingRisk();
        RiskAssessmentCompletedData other = new RiskAssessmentCompletedData(
                UUID.randomUUID(),
                RiskDecisionValue.APPROVED,
                0,
                RiskLevelValue.LOW,
                List.of(),
                "risk-v1");

        assertThatThrownBy(() -> policy.apply(payment, other, ASSESSED_AT))
                .isInstanceOf(RiskAssessmentPaymentMismatchException.class)
                .hasMessageContaining("does not match");
        assertThat(payment.status()).isEqualTo(PaymentStatus.RISK_CHECKING);
    }

    private static RiskAssessmentCompletedData assessment(RiskDecisionValue decision) {
        return new RiskAssessmentCompletedData(
                PAYMENT_ID, decision, 0, RiskLevelValue.LOW, List.of(), "risk-v1");
    }

    private static Payment paymentCheckingRisk() {
        Payment payment = Payment.create(
                new MerchantSnapshot(
                        MERCHANT_ID,
                        MerchantStatus.ACTIVE,
                        "VND",
                        Money.of("50000000", "VND")),
                new PaymentIntake(
                        PAYMENT_ID,
                        UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95"),
                        UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3"),
                        "ORDER-1",
                        "idempotency-key",
                        Money.of("500000", "VND"),
                        null,
                        Map.of(),
                        CREATED_AT));
        payment.submitForRisk(CREATED_AT);
        return payment;
    }
}
