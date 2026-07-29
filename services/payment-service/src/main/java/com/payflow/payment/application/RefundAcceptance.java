package com.payflow.payment.application;

import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.domain.model.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable body stored and replayed for refund intake. */
public record RefundAcceptance(
        UUID refundId,
        UUID paymentId,
        RefundStatus status,
        BigDecimal amount,
        String currency,
        Instant createdAt) {

    public RefundAcceptance {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static RefundAcceptance of(Refund refund) {
        return new RefundAcceptance(
                refund.id(),
                refund.paymentId(),
                refund.status(),
                refund.amount().amount(),
                refund.amount().currency(),
                refund.createdAt());
    }
}
