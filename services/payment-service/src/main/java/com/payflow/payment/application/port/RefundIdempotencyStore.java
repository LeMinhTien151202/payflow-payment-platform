package com.payflow.payment.application.port;

import com.payflow.payment.application.idempotency.RefundIdempotentResponse;
import java.time.Instant;
import java.util.Optional;

/** Endpoint-specific view of the shared idempotency table for refund response JSON. */
public interface RefundIdempotencyStore {

    Optional<RefundIdempotentResponse> find(String scope, String idempotencyKey);

    void record(
            String scope,
            String idempotencyKey,
            RefundIdempotentResponse response,
            Instant expiresAt);
}
