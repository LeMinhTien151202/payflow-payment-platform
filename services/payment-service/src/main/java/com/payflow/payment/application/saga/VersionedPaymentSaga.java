package com.payflow.payment.application.saga;

import com.payflow.payment.domain.model.PaymentSaga;
import java.util.Objects;

/** Saga workflow snapshot paired with the optimistic version that was read. */
public record VersionedPaymentSaga(PaymentSaga saga, long version) {

    public VersionedPaymentSaga {
        Objects.requireNonNull(saga, "saga");
        if (version < 0) {
            throw new IllegalArgumentException("Saga version cannot be negative");
        }
    }
}
