package com.payflow.account.infrastructure.persistence;

import com.payflow.account.domain.model.Money;
import com.payflow.account.domain.model.RefundCredit;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "refund_credits", schema = "account")
class RefundCreditEntity {

    @Id
    private UUID id;

    @Column(name = "refund_id", nullable = false)
    private UUID refundId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "journal_id", nullable = false)
    private UUID journalId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    // CHAR(3) trong DDL migration, do đó mapping ở đây cũng phải là CHAR. Nếu không có điều này, Hibernate
    // sẽ mong đợi varchar(3), tìm thấy bpchar trong DB, và ddl-auto=validate từ chối khởi chạy service.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "credited_at", nullable = false)
    private Instant creditedAt;

    protected RefundCreditEntity() {}

    static RefundCreditEntity from(RefundCredit credit) {
        RefundCreditEntity entity = new RefundCreditEntity();
        entity.id = credit.id();
        entity.refundId = credit.refundId();
        entity.paymentId = credit.paymentId();
        entity.accountId = credit.accountId();
        entity.journalId = credit.journalId();
        entity.amount = credit.amount().amount();
        entity.currency = credit.amount().currency();
        entity.creditedAt = credit.creditedAt();
        return entity;
    }

    RefundCredit toCredit() {
        return new RefundCredit(
                id,
                refundId,
                paymentId,
                accountId,
                journalId,
                new Money(amount, currency),
                creditedAt);
    }
}
