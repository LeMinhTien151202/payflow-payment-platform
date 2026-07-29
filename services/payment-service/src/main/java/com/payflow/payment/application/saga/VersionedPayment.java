package com.payflow.payment.application.saga;

import com.payflow.payment.domain.model.Payment;
import java.util.Objects;

/** Payment workflow snapshot paired with the persistence version that was read. */
public record VersionedPayment(Payment payment, long version) {

    public VersionedPayment {
        Objects.requireNonNull(payment, "payment");
        if (version < 0) {
            throw new IllegalArgumentException("payment version cannot be negative");
        }
    }
}
