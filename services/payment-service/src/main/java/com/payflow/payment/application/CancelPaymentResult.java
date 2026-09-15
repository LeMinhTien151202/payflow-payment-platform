package com.payflow.payment.application;

import java.util.Objects;

/** New or replayed result of an idempotent payment cancellation. */
public sealed interface CancelPaymentResult {

    PaymentAcceptance payment();

    int responseStatus();

    record Cancelled(PaymentAcceptance payment) implements CancelPaymentResult {
        public static final int STATUS = 200;

        public Cancelled {
            Objects.requireNonNull(payment, "payment");
        }

        @Override
        public int responseStatus() {
            return STATUS;
        }
    }

    record Replayed(PaymentAcceptance payment, int responseStatus) implements CancelPaymentResult {
        public Replayed {
            Objects.requireNonNull(payment, "payment");
        }
    }
}
