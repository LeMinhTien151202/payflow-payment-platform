package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.RefundAcceptance;
import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.idempotency.RefundIdempotentResponse;
import com.payflow.payment.application.port.RefundIdempotencyStore;
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

/** Endpoint-specific JSON adapter over the shared idempotency table. */
@Component
class JpaRefundIdempotencyStore implements RefundIdempotencyStore {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    JpaRefundIdempotencyStore(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public Optional<RefundIdempotentResponse> find(String scope, String idempotencyKey) {
        List<IdempotencyRecordEntity> rows = entityManager
                .createQuery(
                        "select r from IdempotencyRecordEntity r"
                                + " where r.scope = :scope and r.idempotencyKey = :key",
                        IdempotencyRecordEntity.class)
                .setParameter("scope", scope)
                .setParameter("key", idempotencyKey)
                .getResultList();
        if (rows.isEmpty() || rows.getFirst().status() != IdempotencyStatus.COMPLETED) {
            return Optional.empty();
        }
        IdempotencyRecordEntity row = rows.getFirst();
        return Optional.of(
                new RefundIdempotentResponse(
                        row.requestHash(),
                        row.resourceId(),
                        row.responseStatus(),
                        objectMapper.readValue(row.responseBody(), RefundAcceptance.class)));
    }

    @Override
    public void record(
            String scope,
            String idempotencyKey,
            RefundIdempotentResponse response,
            Instant expiresAt) {
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
                        response.body().createdAt()));
        try {
            entityManager.flush();
        } catch (PersistenceException failure) {
            if (ConstraintViolations.violated(failure, "uq_idempotency_records_scope_key")) {
                throw new ConcurrentIdempotentRequestException(scope, idempotencyKey, failure);
            }
            throw failure;
        }
    }
}
