package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.exception.DuplicateMerchantReferenceException;
import com.payflow.payment.application.exception.ConcurrentSagaUpdateException;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.port.PaymentRepository;
import com.payflow.payment.application.port.RefundPaymentStore;
import com.payflow.payment.application.port.PaymentWorkflowStore;
import com.payflow.payment.application.saga.VersionedPayment;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentStatusChange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the payment aggregate and its status history.
 *
 * <p>Annotated {@code @Component} rather than {@code @Repository} on purpose. {@code @Repository} would add
 * Spring's exception-translation proxy, so a constraint failure would arrive as a
 * {@code DataIntegrityViolationException} in some call paths and a {@code PersistenceException} in others; the
 * translation below has to walk a known exception shape to find the constraint name.
 *
 * <p>No transaction annotation either. This runs inside the transaction the use case opened, and a boundary
 * here would either join it silently or — worse, if anyone ever changed the propagation — commit the payment
 * separately from its outbox event.
 */
@Component
class JpaPaymentRepository implements PaymentRepository, PaymentWorkflowStore, RefundPaymentStore {

    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() {};

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    JpaPaymentRepository(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(Payment payment) {
        entityManager.persist(PaymentEntity.from(payment, metadataJson(payment)));

        for (PaymentStatusChange change : payment.recordedStatusChanges()) {
            entityManager.persist(
                    PaymentStatusHistoryEntity.from(UUID.randomUUID(), payment.id(), change));
        }

        try {
            // Flushed here, not left to commit. A unique-index violation discovered at commit time is thrown
            // from inside the transaction template, past every catch block that knows what it means — and the
            // difference between "replay the winner's response" and "500" is exactly that catch block.
            entityManager.flush();
        } catch (PersistenceException failure) {
            throw translate(failure, payment);
        }
    }

    /**
     * Both keys are in the WHERE clause. Loading by id and comparing the merchant afterwards would give the
     * same answer today and would stop doing so the first time someone reused the load and forgot the
     * comparison; a query that cannot be run without the merchant has no such version.
     */
    @Override
    public Optional<Payment> find(UUID paymentId, UUID merchantId) {
        return entityManager
                .createQuery(
                        "select p from PaymentEntity p where p.id = :id and p.merchantId = :merchantId",
                        PaymentEntity.class)
                .setParameter("id", paymentId)
                .setParameter("merchantId", merchantId)
                .getResultList()
                .stream()
                .findFirst()
                .map(row -> row.toPayment(metadata(row.metadata())));
    }

    @Override
    public Optional<VersionedPayment> findForWorkflow(UUID paymentId) {
        return entityManager
                .createQuery(
                        "select p from PaymentEntity p where p.id = :id",
                        PaymentEntity.class)
                .setParameter("id", paymentId)
                .getResultList()
                .stream()
                .findFirst()
                .map(row -> new VersionedPayment(
                        row.toPayment(metadata(row.metadata())), row.version()));
    }

    @Override
    public Optional<Payment> findForRefund(UUID paymentId, UUID merchantId) {
        return entityManager
                .createQuery(
                        "select p from PaymentEntity p where p.id = :id and p.merchantId = :merchantId",
                        PaymentEntity.class)
                .setParameter("id", paymentId)
                .setParameter("merchantId", merchantId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .stream()
                .findFirst()
                .map(row -> row.toPayment(metadata(row.metadata())));
    }

    @Override
    public Optional<Payment> findForRefundWorkflow(UUID paymentId) {
        return entityManager
                .createQuery(
                        "select p from PaymentEntity p where p.id = :id",
                        PaymentEntity.class)
                .setParameter("id", paymentId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultList()
                .stream()
                .findFirst()
                .map(row -> row.toPayment(metadata(row.metadata())));
    }

    @Override
    public void updateRefundState(Payment payment) {
        PaymentEntity entity = entityManager.find(PaymentEntity.class, payment.id());
        if (entity == null) {
            throw new IllegalStateException("locked payment disappeared before refund update");
        }
        entity.applyWorkflowState(payment);
        for (PaymentStatusChange change : payment.recordedStatusChanges()) {
            entityManager.persist(
                    PaymentStatusHistoryEntity.from(UUID.randomUUID(), payment.id(), change));
        }
        entityManager.flush();
    }

    @Override
    public void updateWorkflow(VersionedPayment stored) {
        Payment payment = stored.payment();
        PaymentEntity entity = entityManager.find(PaymentEntity.class, payment.id());
        if (entity == null || entity.version() != stored.version()) {
            throw new ConcurrentSagaUpdateException("Payment", payment.id(), stored.version());
        }

        entity.applyWorkflowState(payment);
        for (PaymentStatusChange change : payment.recordedStatusChanges()) {
            entityManager.persist(
                    PaymentStatusHistoryEntity.from(UUID.randomUUID(), payment.id(), change));
        }

        try {
            entityManager.flush();
        } catch (OptimisticLockException failure) {
            throw new ConcurrentSagaUpdateException(
                    "Payment", payment.id(), stored.version(), failure);
        }
    }

    /**
     * Empty metadata becomes SQL NULL rather than {@code {}}. Both would satisfy the {@code jsonb_typeof}
     * check, and NULL is what the column comment describes: the merchant sent nothing.
     */
    private String metadataJson(Payment payment) {
        return payment.metadata().isEmpty() ? null : objectMapper.writeValueAsString(payment.metadata());
    }

    /** The other direction: a NULL column is no metadata, which the aggregate holds as an empty map. */
    private Map<String, String> metadata(String json) {
        return json == null ? Map.of() : objectMapper.readValue(json, METADATA);
    }

    /**
     * Turns a named index violation into the business outcome it represents, and rethrows anything else
     * untouched. An unrecognised constraint failure is a bug in this service, and disguising it as a client
     * error would hide it behind a 409 that the client can do nothing about.
     */
    private RuntimeException translate(PersistenceException failure, Payment payment) {
        if (ConstraintViolations.violated(failure, "uq_payments_merchant_reference")) {
            return new DuplicateMerchantReferenceException(
                    payment.merchantId(), payment.merchantReference(), failure);
        }
        if (ConstraintViolations.violated(failure, "uq_payments_merchant_idempotency_key")) {
            // Reachable only if the idempotency record and the payment disagree — the record insert happens
            // first and guards the same key. Kept because this index is the harder guarantee of the two: one
            // key produces at most one payment even if the record table is ever wrong.
            return new ConcurrentIdempotentRequestException(
                    IdempotencyScope.createPayment(payment.merchantId()),
                    payment.idempotencyKey(),
                    failure);
        }
        return failure;
    }
}
