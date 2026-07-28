package com.payflow.events.payment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Payload of {@code payment.failed} v1, per spec §8.4. */
public record PaymentFailedData(UUID paymentId, String failureCode, Instant failedAt) {

    public static final int MAX_FAILURE_CODE_LENGTH = 100;

    public PaymentFailedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(failedAt, "failedAt is required");
        if (failureCode == null
                || failureCode.isBlank()
                || failureCode.length() > MAX_FAILURE_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "failureCode must be non-blank and at most "
                            + MAX_FAILURE_CODE_LENGTH
                            + " characters");
        }
    }
}
