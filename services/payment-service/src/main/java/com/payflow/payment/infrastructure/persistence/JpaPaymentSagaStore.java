package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.exception.ConcurrentSagaUpdateException;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.saga.VersionedPaymentSaga;
import com.payflow.payment.domain.model.PaymentSaga;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** JPA adapter for durable Saga state; every update is guarded by the version originally read. */
@Component
class JpaPaymentSagaStore implements PaymentSagaStore {

    private static final List<PaymentSagaStatus> AUTOMATED_STATUSES =
            List.of(PaymentSagaStatus.RUNNING, PaymentSagaStatus.COMPENSATING);

    private final EntityManager entityManager;

    JpaPaymentSagaStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void add(PaymentSaga saga) {
        entityManager.persist(PaymentSagaEntity.from(saga));
    }

    @Override
    public Optional<VersionedPaymentSaga> find(UUID sagaId) {
        PaymentSagaEntity entity = entityManager.find(PaymentSagaEntity.class, sagaId);
        return entity == null
                ? Optional.empty()
                : Optional.of(new VersionedPaymentSaga(entity.toSaga(), entity.version()));
    }

    @Override
    public Optional<VersionedPaymentSaga> findByPaymentId(UUID paymentId) {
        return entityManager
                .createQuery(
                        "select s from PaymentSagaEntity s where s.paymentId = :paymentId",
                        PaymentSagaEntity.class)
                .setParameter("paymentId", paymentId)
                .getResultList()
                .stream()
                .findFirst()
                .map(entity -> new VersionedPaymentSaga(entity.toSaga(), entity.version()));
    }

    @Override
    public Optional<VersionedPaymentSaga> findByPaymentIdForCancellation(UUID paymentId) {
        return entityManager
                .createQuery(
                        "select s from PaymentSagaEntity s where s.paymentId = :paymentId",
                        PaymentSagaEntity.class)
                .setParameter("paymentId", paymentId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .stream()
                .findFirst()
                .map(entity -> new VersionedPaymentSaga(entity.toSaga(), entity.version()));
    }

    @Override
    public List<UUID> findDueIds(Instant dueAt, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Saga due limit must be between 1 and 1000");
        }
        return entityManager
                .createQuery(
                        "select s.id from PaymentSagaEntity s"
                                + " where s.status in :statuses and s.deadlineAt <= :dueAt"
                                + " order by s.deadlineAt, s.updatedAt, s.id",
                        UUID.class)
                .setParameter("statuses", AUTOMATED_STATUSES)
                .setParameter("dueAt", dueAt)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public void update(VersionedPaymentSaga stored) {
        PaymentSaga saga = stored.saga();
        PaymentSagaEntity entity = entityManager.find(PaymentSagaEntity.class, saga.id());
        if (entity == null || entity.version() != stored.version()) {
            throw new ConcurrentSagaUpdateException("PaymentSaga", saga.id(), stored.version());
        }
        entity.apply(saga);
        try {
            entityManager.flush();
        } catch (OptimisticLockException failure) {
            throw new ConcurrentSagaUpdateException(
                    "PaymentSaga", saga.id(), stored.version(), failure);
        }
    }
}
