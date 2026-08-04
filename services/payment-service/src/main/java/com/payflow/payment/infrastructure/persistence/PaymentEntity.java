package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentFeeSnapshot;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Dòng dữ liệu tương ứng trong {@code payment.payments}.
 *
 * <p>Tách biệt với {@link Payment} chứ không dùng annotation trực tiếp trên aggregate. Aggregate không có field {@code version},
 * lưu giữ các thay đổi trạng thái được ghi nhận trong một danh sách mà JPA không có cột tương ứng, và tự validate chính nó trong
 * một constructor mà JPA không được phép dùng. Việc map trực tiếp sẽ đồng nghĩa với việc cung cấp cho nó một constructor không tham số và
 * các field có thể thay đổi (mutable fields) — cũng có nghĩa là làm mất đi lý do nó tồn tại.
 *
 * <p>{@code metadata} là một chuỗi JSON ở đây, chứ không phải một {@code Map}. Adapter serialise nó bằng
 * {@code ObjectMapper} của application, do đó những gì đi vào cột DB được quyết định bởi code có thể đọc được, chứ
 * không phụ thuộc vào định dạng JSON mapper nào mà Hibernate vô tình giải mã.
 */
@Entity
@Table(name = "payments", schema = "payment")
class PaymentEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "merchant_reference", nullable = false, length = 100)
    private String merchantReference;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "fee_policy_version", nullable = false, length = 100)
    private String feePolicyVersion;

    @Column(name = "applied_fee_rate", nullable = false, precision = 8, scale = 6)
    private BigDecimal appliedFeeRate;

    @Column(name = "fee_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal feeAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fee_currency", nullable = false, length = 3)
    private String feeCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "fee_rounding_mode", nullable = false, length = 20)
    private RoundingMode feeRoundingMode;

    @Column(name = "total_refunded_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalRefundedAmount;

    @Column(name = "reserved_refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal reservedRefundAmount;

    @Column(name = "total_fee_reversed_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalFeeReversedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    private PaymentStatus status;

    @Column(name = "description", length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Khóa lạc quan (Optimistic lock). Chưa dùng trong Phase 1A, vốn chỉ insert, và hiện diện để chuyển đổi
     * trạng thái đầu tiên của Phase 1B không thể được ghi nếu thiếu nó.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected PaymentEntity() {
        // Bắt buộc bởi JPA.
    }

    /**
     * @param metadataJson metadata của aggregate đã được serialised, hoặc {@code null} khi nó rỗng —
     *     một object JSON rỗng và việc không có metadata là cùng một sự thật, và NULL là thứ mà comment
     *     cột DB mô tả
     */
    static PaymentEntity from(Payment payment, String metadataJson) {
        PaymentEntity entity = new PaymentEntity();
        entity.id = payment.id();
        entity.merchantId = payment.merchantId();
        entity.customerId = payment.customerId();
        entity.sourceAccountId = payment.sourceAccountId();
        entity.merchantReference = payment.merchantReference();
        entity.idempotencyKey = payment.idempotencyKey();
        entity.amount = payment.amount().amount();
        entity.currency = payment.amount().currency();
        entity.feePolicyVersion = payment.feeSnapshot().policyVersion();
        entity.appliedFeeRate = payment.feeSnapshot().appliedRate();
        entity.feeAmount = payment.feeSnapshot().feeAmount().amount();
        entity.feeCurrency = payment.feeSnapshot().feeAmount().currency();
        entity.feeRoundingMode = payment.feeSnapshot().roundingMode();
        entity.totalRefundedAmount = payment.totalRefundedAmount().amount();
        entity.reservedRefundAmount = payment.reservedRefundAmount().amount();
        entity.totalFeeReversedAmount = payment.totalFeeReversedAmount().amount();
        entity.status = payment.status();
        entity.description = payment.description();
        entity.metadata = metadataJson;
        entity.createdAt = payment.createdAt();
        entity.updatedAt = payment.updatedAt();
        return entity;
    }

    /**
     * Dựng lại aggregate.
     *
     * <p>Đi qua {@link PaymentIntake}, nơi tái validate những gì nó được cung cấp. Điều đó là cố ý: một dòng
     * không còn thỏa mãn các quy tắc intake — số tiền không dương, một loại currency mà nền tảng đã dừng
     * hỗ trợ — là dữ liệu hỏng, và việc phát hiện khi đọc nó vẫn tốt hơn là tiếp tục truyền nó đi.
     *
     * @param metadata cột {@code metadata} đã được deserialised, rỗng khi cột DB là NULL
     */
    Payment toPayment(Map<String, String> metadata) {
        return Payment.rehydrate(
                id,
                merchantId,
                new PaymentIntake(
                        id,
                        customerId,
                        sourceAccountId,
                        merchantReference,
                        idempotencyKey,
                        new Money(amount, currency),
                        description,
                        metadata,
                        createdAt),
                new PaymentFeeSnapshot(
                        feePolicyVersion,
                        appliedFeeRate,
                        new Money(feeAmount, feeCurrency),
                        feeRoundingMode),
                status,
                new Money(totalRefundedAmount, currency),
                new Money(reservedRefundAmount, currency),
                new Money(totalFeeReversedAmount, feeCurrency),
                updatedAt);
    }

    void applyWorkflowState(Payment payment) {
        if (!id.equals(payment.id())) {
            throw new IllegalArgumentException("cannot apply a different Payment aggregate");
        }
        status = payment.status();
        totalRefundedAmount = payment.totalRefundedAmount().amount();
        reservedRefundAmount = payment.reservedRefundAmount().amount();
        totalFeeReversedAmount = payment.totalFeeReversedAmount().amount();
        updatedAt = payment.updatedAt();
    }

    UUID id() {
        return id;
    }

    /** The raw JSON, for the adapter to deserialise with the application's {@code ObjectMapper}. */
    String metadata() {
        return metadata;
    }

    long version() {
        return version;
    }
}
