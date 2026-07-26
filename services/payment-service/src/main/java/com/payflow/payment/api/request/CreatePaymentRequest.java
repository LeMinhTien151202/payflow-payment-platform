package com.payflow.payment.api.request;

import com.payflow.payment.application.command.CreatePaymentCommand;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.PaymentIntake;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Public request body for {@code POST /api/v1/payments}. */
public record CreatePaymentRequest(
        @NotBlank @Size(max = PaymentIntake.MAX_MERCHANT_REFERENCE_LENGTH)
                String merchantReference,
        @NotNull UUID customerId,
        @NotNull UUID sourceAccountId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = Money.SCALE)
                BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @Size(max = PaymentIntake.MAX_DESCRIPTION_LENGTH) String description,
        @Size(max = PaymentIntake.MAX_METADATA_ENTRIES)
                Map<
                                @NotBlank @Size(max = PaymentIntake.MAX_METADATA_KEY_LENGTH) String,
                                @NotNull @Size(max = PaymentIntake.MAX_METADATA_VALUE_LENGTH) String>
                        metadata) {

    /** Maps the public body plus server-authenticated context into the application command. */
    public CreatePaymentCommand toCommand(UUID merchantId, String idempotencyKey) {
        return new CreatePaymentCommand(
                merchantId,
                idempotencyKey,
                merchantReference,
                customerId,
                sourceAccountId,
                amount,
                currency,
                description,
                metadata);
    }
}
