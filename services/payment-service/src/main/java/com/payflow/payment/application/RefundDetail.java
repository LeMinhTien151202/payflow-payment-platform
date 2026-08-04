package com.payflow.payment.application;

import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.domain.model.RefundStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Merchant-visible refund state, including immutable financial workflow references. */
@Schema(description = "Current state of one merchant-owned refund")
public record RefundDetail(
        UUID refundId,
        UUID paymentId,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        String reason,
        UUID ledgerJournalId,
        UUID accountCreditId,
        BigDecimal feeReversalAmount,
        String failureCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {

    public static RefundDetail of(Refund refund) {
        return new RefundDetail(
                refund.id(),
                refund.paymentId(),
                refund.amount().amount(),
                refund.amount().currency(),
                refund.status(),
                refund.reason(),
                refund.ledgerJournalId(),
                refund.accountCreditId(),
                refund.feeReversalAmount() == null
                        ? null
                        : refund.feeReversalAmount().amount(),
                refund.failureCode(),
                refund.createdAt(),
                refund.updatedAt(),
                refund.completedAt());
    }
}
