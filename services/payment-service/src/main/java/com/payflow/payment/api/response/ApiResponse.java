package com.payflow.payment.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Stable success envelope from spec section 10.2. */
@Schema(description = "Envelope thành công ổn định của mọi Payment API")
public record ApiResponse<T>(
        @Schema(description = "Dữ liệu nghiệp vụ") T data,
        @Schema(description = "Metadata để trace request") ResponseMeta meta) {
}
