package com.payflow.events.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentCancelledDataTest {

    @Test
    void exposesAStableVersionedContractWithTheCancellingActor() {
        UUID paymentId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID merchantId = UUID.fromString("20000000-0000-0000-0000-000000000001");
        Instant cancelledAt = Instant.parse("2026-09-10T10:00:00Z");

        PaymentCancelledData data =
                new PaymentCancelledData(paymentId, merchantId, "merchant-operator", cancelledAt);

        assertThat(PaymentEvents.PAYMENT_CANCELLED.name()).isEqualTo("payment.cancelled");
        assertThat(PaymentEvents.PAYMENT_CANCELLED.version()).isEqualTo(1);
        assertThat(PaymentEvents.PAYMENT_CANCELLED.aggregateType()).isEqualTo("PAYMENT");
        assertThat(data.cancelledBy()).isEqualTo("merchant-operator");
    }

    @Test
    void refusesAnUntraceableActor() {
        assertThatThrownBy(() -> new PaymentCancelledData(
                        UUID.randomUUID(), UUID.randomUUID(), " ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
