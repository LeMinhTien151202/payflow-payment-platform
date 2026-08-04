package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.application.PaymentSearchQuery;
import com.payflow.payment.application.port.PaymentSearchPage;
import com.payflow.payment.application.port.PaymentSearchPort;
import com.payflow.payment.domain.model.Payment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** JPA read adapter for deterministic, bounded payment search. */
@Component
class JpaPaymentSearchAdapter implements PaymentSearchPort {

    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() {};

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;

    JpaPaymentSearchAdapter(EntityManager entityManager, ObjectMapper objectMapper) {
        this.entityManager = entityManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentSearchPage search(PaymentSearchQuery criteria) {
        String whereClause = whereClause(criteria);

        TypedQuery<PaymentEntity> pageQuery = entityManager.createQuery(
                "select p from PaymentEntity p"
                        + whereClause
                        + " order by p.createdAt desc, p.id desc",
                PaymentEntity.class);
        bind(pageQuery, criteria);
        pageQuery.setFirstResult(criteria.offset());
        pageQuery.setMaxResults(criteria.size());

        TypedQuery<Long> countQuery = entityManager.createQuery(
                "select count(p.id) from PaymentEntity p" + whereClause, Long.class);
        bind(countQuery, criteria);

        List<Payment> payments = pageQuery.getResultList().stream()
                .map(row -> row.toPayment(metadata(row.metadata())))
                .toList();
        return new PaymentSearchPage(payments, countQuery.getSingleResult());
    }

    private static String whereClause(PaymentSearchQuery criteria) {
        StringBuilder clause = new StringBuilder(" where p.merchantId = :merchantId");
        if (criteria.status() != null) {
            clause.append(" and p.status = :status");
        }
        if (criteria.from() != null) {
            clause.append(" and p.createdAt >= :from");
        }
        if (criteria.to() != null) {
            clause.append(" and p.createdAt < :to");
        }
        return clause.toString();
    }

    private static void bind(Query query, PaymentSearchQuery criteria) {
        query.setParameter("merchantId", criteria.merchantId());
        if (criteria.status() != null) {
            query.setParameter("status", criteria.status());
        }
        if (criteria.from() != null) {
            query.setParameter("from", criteria.from());
        }
        if (criteria.to() != null) {
            query.setParameter("to", criteria.to());
        }
    }

    private Map<String, String> metadata(String json) {
        return json == null ? Map.of() : objectMapper.readValue(json, METADATA);
    }
}
