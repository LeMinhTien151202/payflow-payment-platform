package com.payflow.payment.application.audit;

import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import java.util.UUID;

/** Explicit allowlist of non-secret facts permitted in a payment manual-review audit snapshot. */
public record PaymentReviewAuditFacts(
        PaymentStatus paymentStatus,
        PaymentSagaStatus sagaStatus,
        PaymentSagaStep sagaStep,
        UUID reservationId,
        UUID journalId,
        int retryCount,
        String stableReasonCode) {

    public PaymentReviewAuditFacts {
        if (paymentStatus == null || sagaStatus == null || sagaStep == null) {
            throw new IllegalArgumentException("payment and saga audit states are required");
        }
        if (retryCount < 0) {
            throw new IllegalArgumentException("audit retryCount cannot be negative");
        }
        if (stableReasonCode != null && !stableReasonCode.matches("[A-Z][A-Z0-9_]{0,99}")) {
            throw new IllegalArgumentException("audit reason must be a stable code");
        }
    }
}
