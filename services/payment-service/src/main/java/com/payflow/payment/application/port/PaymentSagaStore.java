package com.payflow.payment.application.port;

import com.payflow.payment.application.saga.VersionedPaymentSaga;
import com.payflow.payment.domain.model.PaymentSaga;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable Payment-owned Saga state with optimistic update semantics. */
public interface PaymentSagaStore {

    void add(PaymentSaga saga);

    Optional<VersionedPaymentSaga> find(UUID sagaId);

    Optional<VersionedPaymentSaga> findByPaymentId(UUID paymentId);

    /** Locks the Saga after the corresponding payment row has been locked for cancellation. */
    Optional<VersionedPaymentSaga> findByPaymentIdForCancellation(UUID paymentId);

    List<UUID> findDueIds(Instant dueAt, int limit);

    void update(VersionedPaymentSaga saga);
}
