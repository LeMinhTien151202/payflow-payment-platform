package com.payflow.payment.application.operations;

import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/** Committed state returned after an audited resolution transaction. */
public record ManualReviewResolutionResult(
        UUID paymentId,
        PaymentStatus paymentStatus,
        PaymentSagaStatus sagaStatus,
        PaymentSagaStep sagaStep,
        UUID commandEventId,
        Instant resolvedAt) {}
