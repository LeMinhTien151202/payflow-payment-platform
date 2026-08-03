package com.payflow.payment.application;

import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A payment as its owning merchant reads it: the {@code data} member of {@code GET /api/v1/payments/{id}}.
 *
 * <p>Unlike {@link PaymentAcceptance} this does echo {@code description} and {@code metadata}. The reason
 * those are left out of the acceptance body is that it gets stored and replayed; here the merchant is asking
 * to see its own payment, and the fields it sent are what makes the response useful for reconciliation.
 *
 * <p>{@code idempotencyKey} is not here. It is the client's own bookkeeping for a request that has already
 * completed, and it is not a property of the payment.
 *
 * <p>Built from the aggregate rather than from a dedicated projection. A separate read model earns its keep
 * when the read shape stops matching the aggregate — a joined view, a computed total, a field the aggregate
 * does not hold. None of that is true yet, and two mapping paths to the same table is how a read view starts
 * quietly disagreeing with a write.
 */
@Schema(description = "Ảnh chụp payment hiện tại dành cho merchant sở hữu")
public record PaymentDetail(
        @Schema(description = "ID payment", format = "uuid") UUID paymentId,
        @Schema(description = "Merchant sở hữu, lấy từ JWT khi tạo", format = "uuid") UUID merchantId,
        @Schema(description = "Mã đơn hàng phía merchant", example = "ORDER-2026-00001")
                String merchantReference,
        @Schema(description = "Khách hàng thanh toán", format = "uuid") UUID customerId,
        @Schema(description = "Tài khoản nguồn", format = "uuid") UUID sourceAccountId,
        @Schema(description = "Số tiền payment", example = "500000.0000") BigDecimal amount,
        @Schema(description = "Mã tiền tệ", example = "VND") String currency,
        @Schema(
                        description =
                                "Trạng thái hiện tại của Saga; chỉ SUCCEEDED nghĩa là ledger và capture đều thành công",
                        example = "SUCCEEDED")
                PaymentStatus status,
        @Schema(description = "Nội dung đối soát do merchant gửi") String description,
        @Schema(description = "Metadata do merchant gửi") Map<String, String> metadata,
        @Schema(description = "Thời điểm tạo", format = "date-time") Instant createdAt,
        @Schema(description = "Thời điểm đổi trạng thái gần nhất", format = "date-time") Instant updatedAt) {

    public static PaymentDetail of(Payment payment) {
        return new PaymentDetail(
                payment.id(),
                payment.merchantId(),
                payment.merchantReference(),
                payment.customerId(),
                payment.sourceAccountId(),
                payment.amount().amount(),
                payment.amount().currency(),
                payment.status(),
                payment.description(),
                payment.metadata(),
                payment.createdAt(),
                payment.updatedAt());
    }
}
