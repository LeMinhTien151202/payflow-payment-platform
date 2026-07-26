package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.MerchantStatus;
import com.payflow.payment.domain.model.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The merchant catalog row, mapped read-only and only as far as payment intake needs it.
 *
 * <p>{@code code}, {@code name}, and the audit timestamps exist in {@code merchant.merchants} and are
 * deliberately not mapped. This service never reads them, and {@code ddl-auto=validate} only checks columns
 * an entity claims — so leaving them out means this mapping cannot break when the merchant module adds a
 * column of its own.
 *
 * <p>No {@code @Version} field for the same reason: payment-service never writes here. When the merchant
 * catalog becomes its own service, this class and its adapter are what get deleted.
 */
@Entity
@Table(name = "merchants", schema = "merchant")
class MerchantEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MerchantStatus status;

    // CHAR(3), not VARCHAR: declared as CHAR so Hibernate's schema validation expects the type the column
    // actually has. A default String mapping would expect varchar and fail validation at startup.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(name = "max_transaction_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal maxTransactionAmount;

    protected MerchantEntity() {
        // Required by JPA.
    }

    /** The immutable snapshot the domain decides on. See {@link MerchantSnapshot}. */
    MerchantSnapshot toSnapshot() {
        return new MerchantSnapshot(
                id, status, defaultCurrency, new Money(maxTransactionAmount, defaultCurrency));
    }
}
