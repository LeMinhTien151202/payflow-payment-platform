package com.payflow.payment.application.port;

import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.idempotency.IdempotentResponse;
import java.time.Instant;
import java.util.Optional;

/**
 * Stores and replays the response of an idempotent request.
 *
 * <p>{@link #record} is called inside the payment's own transaction, which is what makes the record and
 * the payment appear together or not at all. It deliberately offers no "reserve the key first, fill in the
 * response later" step: that two-phase shape needs an {@code IN_PROGRESS} state, and an
 * {@code IN_PROGRESS} row left behind by a crashed request is a key that can never be used again.
 */
public interface IdempotencyStore {

    /**
     * The response stored for this key, or empty if the key is unused.
     *
     * <p>Called before the transaction opens, so the common case — a client that never retries — costs one
     * indexed read and no lock.
     */
    Optional<IdempotentResponse> find(String scope, String idempotencyKey);

    /**
     * Stores the response so a repeat of the same request can be answered without doing the work again.
     *
     * @param expiresAt retention horizon; the row stops being replayable after it, and nothing deletes it
     *     yet (see the column comment in {@code V2__payment_intake.sql})
     * @throws ConcurrentIdempotentRequestException if a concurrent request stored this key first
     */
    void record(String scope, String idempotencyKey, IdempotentResponse response, Instant expiresAt);
}
