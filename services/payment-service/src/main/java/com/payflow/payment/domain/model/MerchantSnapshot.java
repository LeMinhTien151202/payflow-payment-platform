package com.payflow.payment.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * What payment intake knows about a merchant, at the moment it decides.
 *
 * <p>A read-only copy, not a reference to a live entity. The merchant catalog is another bounded
 * context — spec 6 says other contexts receive merchant data as an immutable snapshot — and pulling a
 * mutable merchant object into a payment decision would let the limit change halfway through the
 * check.
 *
 * <p>Carries only the two facts intake actually uses: whether the merchant may transact, and the
 * ceiling per payment. Name, contact details, and fee configuration are deliberately absent; a value
 * object that carries fields nobody reads becomes a reason to pass merchant data around.
 */
public record MerchantSnapshot(
        UUID id, MerchantStatus status, String defaultCurrency, Money maxTransactionAmount) {

    public MerchantSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(defaultCurrency, "defaultCurrency");
        Objects.requireNonNull(maxTransactionAmount, "maxTransactionAmount");

        // Both come from columns that can be updated independently. If they ever disagree, the limit
        // means nothing, and failing here is better than comparing amounts across currencies later.
        if (!maxTransactionAmount.currency().equals(defaultCurrency)) {
            throw new IllegalArgumentException(
                    "merchant limit is in "
                            + maxTransactionAmount.currency()
                            + " but the default currency is "
                            + defaultCurrency);
        }
        if (!maxTransactionAmount.isPositive()) {
            throw new IllegalArgumentException(
                    "merchant limit must be positive: " + maxTransactionAmount);
        }
    }

    public boolean canAcceptPayments() {
        return status.canAcceptPayments();
    }
}
