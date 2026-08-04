package com.payflow.payment.infrastructure.persistence;

import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.domain.model.PaymentStatusChange;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng tương ứng trong {@code payment.payment_status_history}.
 *
 * <p>{@code paymentId} là một UUID đơn thuần, không phải là một {@code @ManyToOne}. Không có gì ở đây chuyển hướng đến payment,
 * và một quan hệ association sẽ khiến một bug lazy-loading biến việc ghi một dòng history thành việc load cả một aggregate.
 *
 * <p>Database từ chối thao tác UPDATE và DELETE trên bảng này thông qua một trigger. Do đó Hibernate không bao giờ phải thực hiện
 * cả hai thao tác này: id được gán, dòng dữ liệu được insert một lần duy nhất, và entity không bao giờ bị sửa đổi sau đó.
 */
@Entity
@Table(name = "payment_status_history", schema = "payment")
class PaymentStatusHistoryEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    /** Null đánh dấu entry ghi nhận sự ra đời của payment. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 40)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 40)
    private PaymentStatus toStatus;

    @Column(name = "reason_code", length = 100)
    private String reasonCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected PaymentStatusHistoryEntity() {
        // Bắt buộc bởi JPA.
    }

    /**
     * @param id một surrogate key không có ý nghĩa nghiệp vụ, nên nó được tạo ở đây thay vì truyền qua
     *     layer application
     */
    static PaymentStatusHistoryEntity from(UUID id, UUID paymentId, PaymentStatusChange change) {
        PaymentStatusHistoryEntity entity = new PaymentStatusHistoryEntity();
        entity.id = id;
        entity.paymentId = paymentId;
        entity.fromStatus = change.from();
        entity.toStatus = change.to();
        entity.reasonCode = change.reasonCode();
        entity.occurredAt = change.occurredAt();
        return entity;
    }
}
