package com.payflow.payment.application;

import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * What the caller is told when a payment is accepted: the {@code data} member of the 202 response in
 * spec 7.4.
 *
 * <p>This is also exactly what gets stored in {@code idempotency_records.response_body}, which is why
 * it is one type and not two. A replay must return the response the original request produced, byte for
 * byte in meaning — if the stored shape and the rendered shape were separate types, a later field added
 * to one of them would make replays quietly disagree with fresh responses.
 *
 * <p>Deliberately excludes {@code merchantReference}, {@code description}, and {@code metadata}. The
 * caller sent those; echoing them back would put merchant-supplied content into a stored row that is
 * read again on every replay, for no benefit.
 *
 * <p>The {@code meta.correlationId} member of the response is not here. It belongs to the request, not
 * to the resource, and a replay must carry the correlation id of the request being replayed rather than
 * the one that happened to create the payment.
 */
@Schema(description = "Biên nhận cho biết payment đã được lưu và chờ Kafka xử lý")
public record PaymentAcceptance(
        @Schema(description = "ID dùng để polling GET payment", format = "uuid") UUID paymentId,
        @Schema(description = "Trạng thái ngay lúc nhận, thường là CREATED", example = "CREATED")
                PaymentStatus status,
        @Schema(description = "Số tiền đã nhận xử lý", example = "500000.0000") BigDecimal amount,
        @Schema(description = "Tiền tệ của payment", example = "VND") String currency,
        @Schema(description = "Thời điểm payment được tạo", format = "date-time") Instant createdAt) {

    public PaymentAcceptance {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** Flattens the aggregate into the caller-facing view. */
    public static PaymentAcceptance of(Payment payment) {
        return new PaymentAcceptance(
                payment.id(),
                payment.status(),
                payment.amount().amount(),
                payment.amount().currency(),
                payment.createdAt());
    }
}
