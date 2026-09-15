package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.operations.ManualReviewItem;
import com.payflow.payment.application.operations.ManualReviewPage;
import com.payflow.payment.application.port.ManualReviewQueryPort;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentStatus;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Component;

/** JPA read adapter for the operations work queue, ordered by longest waiting first. */
@Component
class JpaManualReviewQueryAdapter implements ManualReviewQueryPort {

    private final EntityManager entityManager;

    JpaManualReviewQueryAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public ManualReviewPage search(int page, int size) {
        List<ManualReviewItem> items = entityManager.createQuery(
                        """
                        select new com.payflow.payment.application.operations.ManualReviewItem(
                            p.id, p.merchantId, p.amount, p.currency, s.currentStep,
                            s.retryCount, s.lastErrorCode, s.reservationId, s.journalId, s.updatedAt)
                        from PaymentSagaEntity s, PaymentEntity p
                        where p.id = s.paymentId
                          and p.status = :paymentStatus
                          and s.status = :sagaStatus
                        order by s.updatedAt, p.id
                        """,
                        ManualReviewItem.class)
                .setParameter("paymentStatus", PaymentStatus.MANUAL_REVIEW_REQUIRED)
                .setParameter("sagaStatus", PaymentSagaStatus.MANUAL_REVIEW_REQUIRED)
                .setFirstResult(Math.multiplyExact(page, size))
                .setMaxResults(size)
                .getResultList();

        long total = entityManager.createQuery(
                        """
                        select count(p.id)
                        from PaymentSagaEntity s, PaymentEntity p
                        where p.id = s.paymentId
                          and p.status = :paymentStatus
                          and s.status = :sagaStatus
                        """,
                        Long.class)
                .setParameter("paymentStatus", PaymentStatus.MANUAL_REVIEW_REQUIRED)
                .setParameter("sagaStatus", PaymentSagaStatus.MANUAL_REVIEW_REQUIRED)
                .getSingleResult();
        return new ManualReviewPage(items, total);
    }
}
