package com.payflow.payment.application;

import java.util.Objects;

/**
 * Outcome of a create-payment request: either a payment was created, or a previous identical request's
 * response was returned again.
 *
 * <p>The distinction is kept instead of collapsed into one type because the two are not the same event
 * even though the body is identical. A replay created nothing, so it must not be counted as a payment in
 * a metric, must not be logged as one, and returns the status code the original request returned rather
 * than the one this endpoint always returns.
 *
 * <p>Sealed so that adding a third outcome — a failed request whose failure was itself recorded, for
 * instance — is a compile error at every point that handles the result, rather than a silently
 * unhandled case.
 */
public sealed interface CreatePaymentResult {

    /** The accepted payment, in both cases. */
    PaymentAcceptance payment();

    /** HTTP status to return. Fixed at 202 for a new payment; taken from the stored row for a replay. */
    int responseStatus();

    /** A payment was created and its event appended to the outbox in the same transaction. */
    record Accepted(PaymentAcceptance payment) implements CreatePaymentResult {

        /** Spec 10.5: creating an asynchronously processed resource answers 202. */
        public static final int STATUS = 202;

        public Accepted {
            Objects.requireNonNull(payment, "payment");
        }

        @Override
        public int responseStatus() {
            return STATUS;
        }
    }

    /**
     * The idempotency key had already been used by an identical request, so nothing was created and the
     * stored response is returned unchanged.
     */
    record Replayed(PaymentAcceptance payment, int responseStatus) implements CreatePaymentResult {

        public Replayed {
            Objects.requireNonNull(payment, "payment");
        }
    }
}
