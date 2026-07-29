package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.port.RefundRepository;
import com.payflow.payment.domain.model.Refund;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** JPA adapter for refund persistence inside the caller-owned transaction. */
@Component
class JpaRefundRepository implements RefundRepository {

    private final EntityManager entityManager;

    JpaRefundRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void save(Refund refund) {
        entityManager.persist(RefundEntity.from(refund));
        try {
            entityManager.flush();
        } catch (PersistenceException failure) {
            if (ConstraintViolations.violated(failure, "uq_refunds_payment_idempotency_key")) {
                throw new ConcurrentIdempotentRequestException(
                        IdempotencyScope.createRefund(refund.merchantId()),
                        refund.idempotencyKey(),
                        failure);
            }
            throw failure;
        }
    }

    @Override
    public Optional<Refund> findForWorkflow(UUID refundId) {
        return entityManager
                .createQuery(
                        "select r from RefundEntity r where r.id = :id",
                        RefundEntity.class)
                .setParameter("id", refundId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .stream()
                .findFirst()
                .map(RefundEntity::toRefund);
    }

    @Override
    public void updateWorkflow(Refund refund) {
        RefundEntity entity = entityManager.find(RefundEntity.class, refund.id());
        if (entity == null) {
            throw new IllegalStateException("locked refund disappeared before workflow update");
        }
        entity.applyWorkflowState(refund);
        entityManager.flush();
    }
}
