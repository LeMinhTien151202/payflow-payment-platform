package com.payflow.payment.api.request;

import com.payflow.payment.application.command.CreatePaymentCommand;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.PaymentIntake;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Thông tin merchant gửi để tạo một payment bất đồng bộ")
public record CreatePaymentRequest(
        @NotBlank @Size(max = PaymentIntake.MAX_MERCHANT_REFERENCE_LENGTH)
                @Schema(
                        description = "Mã đơn hàng duy nhất trong phạm vi merchant",
                        example = "ORDER-2026-00001")
                String merchantReference,
        @NotNull
                @Schema(description = "Khách hàng thực hiện thanh toán", format = "uuid")
                UUID customerId,
        @NotNull
                @Schema(
                        description = "Tài khoản nguồn sẽ reserve rồi capture tiền",
                        format = "uuid")
                UUID sourceAccountId,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = Money.SCALE)
                @Schema(description = "Số tiền dương, tối đa 4 chữ số thập phân", example = "500000.0000")
                BigDecimal amount,
        @NotBlank
                @Pattern(regexp = "[A-Z]{3}")
                @Schema(description = "Mã tiền tệ ISO 4217 viết hoa", example = "VND")
                String currency,
        @Size(max = PaymentIntake.MAX_DESCRIPTION_LENGTH)
                @Schema(description = "Nội dung thanh toán để đối soát", example = "Thanh toán đơn hàng")
                String description,
        @Size(max = PaymentIntake.MAX_METADATA_ENTRIES)
                @Schema(
                        description = "Tối đa 20 cặp khóa/giá trị nghiệp vụ do merchant tự định nghĩa",
                        example = "{\"channel\":\"WEB\",\"orderId\":\"ORDER-2026-00001\"}")
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
