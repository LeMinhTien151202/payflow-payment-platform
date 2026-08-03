package com.payflow.payment.application;

import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.domain.model.RefundStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Stable body stored and replayed for refund intake. */
@Schema(description = "Biên nhận cho biết refund đã được lưu và chờ Kafka xử lý")
public record RefundAcceptance(
        @Schema(description = "ID refund mới", format = "uuid") UUID refundId,
        @Schema(description = "Payment gốc", format = "uuid") UUID paymentId,
        @Schema(description = "Trạng thái ngay lúc nhận, thường là CREATED", example = "CREATED")
                RefundStatus status,
        @Schema(description = "Số tiền yêu cầu hoàn", example = "100000.0000") BigDecimal amount,
        @Schema(description = "Tiền tệ kế thừa từ payment", example = "VND") String currency,
        @Schema(description = "Thời điểm refund được tạo", format = "date-time") Instant createdAt) {

    public RefundAcceptance {
        Objects.requireNonNull(refundId, "refundId");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static RefundAcceptance of(Refund refund) {
        return new RefundAcceptance(
                refund.id(),
                refund.paymentId(),
                refund.status(),
                refund.amount().amount(),
                refund.amount().currency(),
                refund.createdAt());
    }
}
