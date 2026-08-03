package com.payflow.payment.api.request;

import com.payflow.payment.application.command.CreateRefundCommand;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Refund;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Public body for {@code POST /api/v1/payments/{paymentId}/refunds}. */
@Schema(description = "Số tiền và lý do hoàn của một payment đã thành công")
public record CreateRefundRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false)
                @Digits(integer = 15, fraction = Money.SCALE)
                @Schema(
                        description = "Số tiền muốn hoàn; tổng các refund không được vượt payment",
                        example = "100000.0000")
                BigDecimal amount,
        @Size(max = Refund.MAX_REASON_LENGTH)
                @Schema(description = "Lý do hoàn tiền để audit", example = "Khách trả lại một phần đơn hàng")
                String reason) {

    public CreateRefundCommand toCommand(
            UUID merchantId,
            String actorId,
            UUID paymentId,
            String idempotencyKey) {
        return new CreateRefundCommand(
                merchantId, actorId, paymentId, idempotencyKey, amount, reason);
    }
}
