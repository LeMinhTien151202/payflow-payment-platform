package com.payflow.payment.application.idempotency;

import com.payflow.payment.application.PaymentAcceptance;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored response, ready to be returned again.
 *
 * <p>Holds the finished body rather than the inputs needed to rebuild it. That is the whole point of the
 * table: a payment that has since moved to {@code SUCCEEDED} would re-render as {@code SUCCEEDED}, so a
 * replay built from the current payment row would contradict the response the client originally received
 * — and a client retrying a request must not be told the state changed because they retried.
 *
 * <p>{@code body} is typed as {@link PaymentAcceptance} because {@code POST /api/v1/payments} is the only
 * idempotent endpoint in Phase 1A. When the second one arrives, this becomes generic; guessing at the
 * generic form now would add a type parameter that every caller has to satisfy for no present benefit.
 *
 * @param requestHash fingerprint of the request that produced this response, used to tell a replay from a
 *     reused key
 * @param resourceId the payment this response describes; also inside {@code body}, and kept separately
 *     because the column is what makes "which payment did that key create" a query rather than a JSON scan
 * @param responseStatus the HTTP status the original request returned
 */
public record IdempotentResponse(
        String requestHash, UUID resourceId, int responseStatus, PaymentAcceptance body) {

    public IdempotentResponse {
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(body, "body");

        if (responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException("responseStatus is not an HTTP status: " + responseStatus);
        }
    }

    /** Whether {@code candidate} is the same request that produced this response. */
    public boolean matches(String candidate) {
        return requestHash.equals(candidate);
    }
}
