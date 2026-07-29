package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.domain.model.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Persistence representation of one refund; no JPA type crosses the adapter boundary. */
@Entity
@Table(name = "refunds", schema = "payment")
class RefundEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "fee_reversal_amount", precision = 19, scale = 4)
    private BigDecimal feeReversalAmount;

    @Column(name = "ledger_journal_id")
    private UUID ledgerJournalId;

    @Column(name = "account_credit_id")
    private UUID accountCreditId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected RefundEntity() {
        // Required by JPA.
    }

    static RefundEntity from(Refund refund) {
        RefundEntity entity = new RefundEntity();
        entity.id = refund.id();
        entity.paymentId = refund.paymentId();
        entity.merchantId = refund.merchantId();
        entity.idempotencyKey = refund.idempotencyKey();
        entity.amount = refund.amount().amount();
        entity.currency = refund.amount().currency();
        entity.reason = refund.reason();
        entity.requestedBy = refund.requestedBy();
        entity.feeReversalAmount =
                refund.feeReversalAmount() == null
                        ? null
                        : refund.feeReversalAmount().amount();
        entity.ledgerJournalId = refund.ledgerJournalId();
        entity.accountCreditId = refund.accountCreditId();
        entity.status = refund.status();
        entity.failureCode = refund.failureCode();
        entity.createdAt = refund.createdAt();
        entity.updatedAt = refund.updatedAt();
        entity.completedAt = refund.completedAt();
        return entity;
    }

    Refund toRefund() {
        return Refund.rehydrate(
                id,
                paymentId,
                merchantId,
                idempotencyKey,
                new Money(amount, currency),
                reason,
                requestedBy,
                status,
                ledgerJournalId,
                accountCreditId,
                feeReversalAmount == null ? null : new Money(feeReversalAmount, currency),
                failureCode,
                createdAt,
                updatedAt,
                completedAt);
    }

    void applyWorkflowState(Refund refund) {
        if (!id.equals(refund.id())) {
            throw new IllegalArgumentException("cannot apply a different Refund aggregate");
        }
        status = refund.status();
        ledgerJournalId = refund.ledgerJournalId();
        accountCreditId = refund.accountCreditId();
        feeReversalAmount = refund.feeReversalAmount() == null
                ? null
                : refund.feeReversalAmount().amount();
        failureCode = refund.failureCode();
        updatedAt = refund.updatedAt();
        completedAt = refund.completedAt();
    }
}
