package com.payflow.payment.application.idempotency;

import com.payflow.payment.application.RefundAcceptance;
import java.util.Objects;
import java.util.UUID;

/** Stored refund response selected by the refund endpoint's idempotency scope. */
public record RefundIdempotentResponse(
        String requestHash, UUID resourceId, int responseStatus, RefundAcceptance body) {

    public RefundIdempotentResponse {
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(body, "body");
        if (responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException("responseStatus is not an HTTP status: " + responseStatus);
        }
    }

    public boolean matches(String candidate) {
        return requestHash.equals(candidate);
    }
}
