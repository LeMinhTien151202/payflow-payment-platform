package com.payflow.payment.application.operations;

import com.payflow.payment.domain.model.PaymentSagaStep;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Safe operational view of one stopped Saga; no customer metadata or secrets are exposed. */
@Schema(description = "One Payment Saga waiting for an operations decision")
public record ManualReviewItem(
        UUID paymentId,
        UUID merchantId,
        BigDecimal amount,
        String currency,
        PaymentSagaStep currentStep,
        int retryCount,
        String lastErrorCode,
        UUID reservationId,
        UUID journalId,
        Instant waitingSince) {}
