package com.payflow.payment.domain.exception;

import com.payflow.payment.domain.model.Money;

/**
 * The requested amount is above the merchant's per-payment ceiling.
 *
 * <p>Both amounts are exposed as {@link Money} rather than baked into a message, so the boundary
 * decides what to disclose. Telling this merchant its own limit is harmless and useful; the same text
 * on a shared or partner-facing endpoint would not be, and that judgement does not belong in the
 * domain.
 */
public final class PaymentLimitExceededException extends PaymentDomainException {

    private final Money requested;
    private final Money limit;

    public PaymentLimitExceededException(Money requested, Money limit) {
        super("requested " + requested + " exceeds the per-payment limit " + limit);
        this.requested = requested;
        this.limit = limit;
    }

    public Money requested() {
        return requested;
    }

    public Money limit() {
        return limit;
    }
}
