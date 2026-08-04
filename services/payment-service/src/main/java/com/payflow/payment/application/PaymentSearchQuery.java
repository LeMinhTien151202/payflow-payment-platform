package com.payflow.payment.application;

import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Validated, merchant-scoped criteria for the payment search use case. */
public record PaymentSearchQuery(
        UUID merchantId,
        PaymentStatus status,
        Instant from,
        Instant to,
        int page,
        int size) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public PaymentSearchQuery {
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        if (from != null && to != null && !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("requested page offset is too large");
        }
    }

    public int offset() {
        return page * size;
    }
}
