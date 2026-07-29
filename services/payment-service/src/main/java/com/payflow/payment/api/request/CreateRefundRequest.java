package com.payflow.payment.api.request;

import com.payflow.payment.application.command.CreateRefundCommand;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Refund;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/** Public body for {@code POST /api/v1/payments/{paymentId}/refunds}. */
public record CreateRefundRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false)
                @Digits(integer = 15, fraction = Money.SCALE)
                BigDecimal amount,
        @Size(max = Refund.MAX_REASON_LENGTH) String reason) {

    public CreateRefundCommand toCommand(
            UUID merchantId,
            String actorId,
            UUID paymentId,
            String idempotencyKey) {
        return new CreateRefundCommand(
                merchantId, actorId, paymentId, idempotencyKey, amount, reason);
    }
}
