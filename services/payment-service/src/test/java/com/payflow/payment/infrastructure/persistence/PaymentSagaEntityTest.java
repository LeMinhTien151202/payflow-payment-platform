package com.payflow.payment.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.payment.domain.model.PaymentSaga;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentSagaEntityTest {

    private static final Instant CREATED = Instant.parse("2026-07-29T01:00:00Z");
    private static final UUID PAYMENT_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void roundTripsTheDurableDomainSnapshot() {
        PaymentSaga saga = PaymentSaga.start(
                UUID.randomUUID(), PAYMENT_ID, CREATED.plusSeconds(30), CREATED);
        saga.recordRiskApproved(CREATED.plusSeconds(60), CREATED.plusSeconds(1));

        PaymentSaga restored = PaymentSagaEntity.from(saga).toSaga();

        assertThat(restored.id()).isEqualTo(saga.id());
        assertThat(restored.paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(restored.currentStep()).isEqualTo(saga.currentStep());
        assertThat(restored.status()).isEqualTo(saga.status());
        assertThat(restored.deadlineAt()).isEqualTo(saga.deadlineAt());
        assertThat(restored.updatedAt()).isEqualTo(saga.updatedAt());
    }

    @Test
    void refusesToApplyAStateFromAnotherSaga() {
        PaymentSagaEntity entity = PaymentSagaEntity.from(PaymentSaga.start(
                UUID.randomUUID(), PAYMENT_ID, CREATED.plusSeconds(30), CREATED));
        PaymentSaga other = PaymentSaga.start(
                UUID.randomUUID(), PAYMENT_ID, CREATED.plusSeconds(30), CREATED);

        assertThatThrownBy(() -> entity.apply(other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different Payment Saga");
    }
}
