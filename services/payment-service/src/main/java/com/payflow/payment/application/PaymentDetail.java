package com.payflow.payment.application;

import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A payment as its owning merchant reads it: the {@code data} member of {@code GET /api/v1/payments/{id}}.
 *
 * <p>Unlike {@link PaymentAcceptance} this does echo {@code description} and {@code metadata}. The reason
 * those are left out of the acceptance body is that it gets stored and replayed; here the merchant is asking
 * to see its own payment, and the fields it sent are what makes the response useful for reconciliation.
 *
 * <p>{@code idempotencyKey} is not here. It is the client's own bookkeeping for a request that has already
 * completed, and it is not a property of the payment.
 *
 * <p>Built from the aggregate rather than from a dedicated projection. A separate read model earns its keep
 * when the read shape stops matching the aggregate — a joined view, a computed total, a field the aggregate
 * does not hold. None of that is true yet, and two mapping paths to the same table is how a read view starts
 * quietly disagreeing with a write.
 */
public record PaymentDetail(
        UUID paymentId,
        UUID merchantId,
        String merchantReference,
        UUID customerId,
        UUID sourceAccountId,
        BigDecimal amount,
        String currency,
        PaymentStatus status,
        String description,
        Map<String, String> metadata,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentDetail of(Payment payment) {
        return new PaymentDetail(
                payment.id(),
                payment.merchantId(),
                payment.merchantReference(),
                payment.customerId(),
                payment.sourceAccountId(),
                payment.amount().amount(),
                payment.amount().currency(),
                payment.status(),
                payment.description(),
                payment.metadata(),
                payment.createdAt(),
                payment.updatedAt());
    }
}
