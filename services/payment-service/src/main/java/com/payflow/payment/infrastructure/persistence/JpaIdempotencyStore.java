package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.PaymentAcceptance;
import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.idempotency.IdempotentResponse;
import com.payflow.payment.application.port.IdempotencyStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores and reads idempotency records, converting the stored body between JSON and
 * {@link PaymentAcceptance}.
 *
 * <p>The conversion lives here so that no part of the application layer imports Jackson. The application
 * decides what a replay means; this class only knows how the answer is written down.
 */
@Component
class JpaIdempotencyStore implements IdempotencyStore {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    JpaIdempotencyStore(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code REQUIRES_NEW} would be wrong and {@code REQUIRED} is not enough. The lookup after a lost race
     * runs when the caller has no transaction and the failed one is gone, so it needs a transaction of its
     * own; {@code SUPPORTS} would leave it running without one, which on PostgreSQL means an implicit
     * single-statement transaction — correct here, but only by accident. {@code REQUIRED} opens one when
     * there is none and joins the payment's when there is.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public Optional<IdempotentResponse> find(String scope, String idempotencyKey) {
        List<IdempotencyRecordEntity> rows =
                entityManager
                        .createQuery(
                                "select r from IdempotencyRecordEntity r"
                                        + " where r.scope = :scope and r.idempotencyKey = :key",
                                IdempotencyRecordEntity.class)
                        .setParameter("scope", scope)
                        .setParameter("key", idempotencyKey)
                        .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        IdempotencyRecordEntity row = rows.getFirst();
        if (row.status() != IdempotencyStatus.COMPLETED) {
            // Nothing writes a non-COMPLETED row in Phase 1A. If one appears, it has no response to replay,
            // and treating the key as unused is the only answer that does not fabricate a response.
            return Optional.empty();
        }

        return Optional.of(
                new IdempotentResponse(
                        row.requestHash(),
                        row.resourceId(),
                        row.responseStatus(),
                        objectMapper.readValue(row.responseBody(), PaymentAcceptance.class)));
    }

    @Override
    public void record(
            String scope, String idempotencyKey, IdempotentResponse response, Instant expiresAt) {

        entityManager.persist(
                IdempotencyRecordEntity.completed(
                        UUID.randomUUID(),
                        scope,
                        idempotencyKey,
                        response.requestHash(),
                        response.resourceId(),
                        response.responseStatus(),
                        objectMapper.writeValueAsString(response.body()),
                        expiresAt,
                        // created_at is the row's own timestamp, and expires_at must be strictly after it.
                        // Taken from the response's payment rather than a fresh clock read so that the
                        // constraint compares two instants from the same moment in the request.
                        response.body().createdAt()));

        try {
            // The insert has to be attempted now, while a caller that knows what a duplicate key means is
            // still on the stack. This flush is the point at which two concurrent requests serialise: the
            // second blocks on uq_idempotency_records_scope_key until the first commits or rolls back.
            entityManager.flush();
        } catch (PersistenceException failure) {
            if (ConstraintViolations.violated(failure, "uq_idempotency_records_scope_key")) {
                throw new ConcurrentIdempotentRequestException(scope, idempotencyKey, failure);
            }
            throw failure;
        }
    }
}
