package com.payflow.payment.application;

import java.util.Objects;

/** New or replayed result of the asynchronous refund-intake endpoint. */
public sealed interface CreateRefundResult {

    RefundAcceptance refund();

    int responseStatus();

    record Accepted(RefundAcceptance refund) implements CreateRefundResult {
        public static final int STATUS = 202;

        public Accepted {
            Objects.requireNonNull(refund, "refund");
        }

        @Override
        public int responseStatus() {
            return STATUS;
        }
    }

    record Replayed(RefundAcceptance refund, int responseStatus) implements CreateRefundResult {
        public Replayed {
            Objects.requireNonNull(refund, "refund");
        }
    }
}
