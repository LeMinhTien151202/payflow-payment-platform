package com.payflow.payment.api.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** Request-scoped metadata returned by successful PayFlow APIs. */
@Schema(description = "Thông tin kỹ thuật của response, không phải trạng thái payment")
public record ResponseMeta(
        @Schema(
                        description = "ID dùng tìm cùng request trong log của gateway và service",
                        example = "api-create-1")
                String correlationId,
        @Schema(description = "Thời điểm service tạo response", format = "date-time") Instant timestamp) {
}
