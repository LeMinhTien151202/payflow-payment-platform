package com.payflow.payment.api;

import com.payflow.observability.CorrelationId;
import com.payflow.payment.api.exception.IdempotencyKeyRequiredException;
import com.payflow.payment.api.request.CreatePaymentRequest;
import com.payflow.payment.api.request.CreateRefundRequest;
import com.payflow.payment.api.response.ApiResponse;
import com.payflow.payment.api.response.ResponseMeta;
import com.payflow.payment.application.CreatePaymentResult;
import com.payflow.payment.application.CreateRefundResult;
import com.payflow.payment.application.PaymentAcceptance;
import com.payflow.payment.application.PaymentDetail;
import com.payflow.payment.application.PaymentSearchQuery;
import com.payflow.payment.application.PaymentSearchResult;
import com.payflow.payment.application.RefundAcceptance;
import com.payflow.payment.application.RefundDetail;
import com.payflow.payment.application.handler.CreatePaymentHandler;
import com.payflow.payment.application.handler.CreateRefundHandler;
import com.payflow.payment.application.handler.GetPaymentHandler;
import com.payflow.payment.application.handler.GetRefundHandler;
import com.payflow.payment.application.handler.SearchPaymentsHandler;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.infrastructure.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public payment API. Authentication context được chuyển đổi tại đây; công việc nghiệp vụ nằm trong các handler. */
@RestController
@RequestMapping("/api/v1/payments")
@Tag(
        name = "Payments",
        description =
                "Nhận payment/refund và đọc trạng thái. Các lệnh ghi chạy bất đồng bộ: HTTP 202 "
                        + "chỉ xác nhận đã lưu yêu cầu, kết quả cuối cùng được đọc bằng GET payment.")
@SecurityRequirement(name = PaymentOpenApiConfig.BEARER_AUTH)
public class PaymentController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String MERCHANT_ID_CLAIM = "merchant_id";

    private final CreatePaymentHandler createPayment;
    private final CreateRefundHandler createRefund;
    private final GetPaymentHandler getPayment;
    private final GetRefundHandler getRefund;
    private final SearchPaymentsHandler searchPayments;
    private final Clock clock;

    public PaymentController(
            CreatePaymentHandler createPayment,
            CreateRefundHandler createRefund,
            GetPaymentHandler getPayment,
            GetRefundHandler getRefund,
            SearchPaymentsHandler searchPayments,
            Clock clock) {
        this.createPayment = createPayment;
        this.createRefund = createRefund;
        this.getPayment = getPayment;
        this.getRefund = getRefund;
        this.searchPayments = searchPayments;
        this.clock = clock;
    }

    @Operation(
            operationId = "getRefund",
            summary = "Read one refund of a merchant-owned payment",
            description =
                    "Requires refundId, parent paymentId and merchant_id from the JWT to match. "
                            + "Returns the same 404 for absent and cross-merchant data, and emits no Kafka event.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Current refund state and committed workflow references",
                useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid bearer token",
                content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Token lacks payment:read or merchant_id",
                content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "Refund absent or not visible to this merchant",
                content = @Content)
    })
    @GetMapping("/{paymentId}/refunds/{refundId}")
    ApiResponse<RefundDetail> getRefund(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paymentId,
            @PathVariable UUID refundId,
            @Parameter(hidden = true) HttpServletRequest servletRequest) {

        return envelope(getRefund.handle(refundId, paymentId, merchantId(jwt)), servletRequest);
    }

    @Operation(
            operationId = "searchPayments",
            summary = "Search payments owned by the authenticated merchant",
            description =
                    "Returns a bounded, newest-first page. merchant_id always comes from the JWT; "
                            + "status and the half-open createdAt interval [from,to) are optional. "
                            + "This read does not publish a Kafka event.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "A merchant-scoped page of payments",
                useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Invalid status, time range, page, or size",
                content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Missing or invalid bearer token",
                content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Token lacks payment:read or merchant_id",
                content = @Content)
    })
    @GetMapping
    ApiResponse<PaymentSearchResult> search(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Optional payment state")
                    @RequestParam(required = false)
                    PaymentStatus status,
            @Parameter(description = "Inclusive createdAt lower bound", example = "2026-07-01T00:00:00Z")
                    @RequestParam(required = false)
                    Instant from,
            @Parameter(description = "Exclusive createdAt upper bound", example = "2026-08-01T00:00:00Z")
                    @RequestParam(required = false)
                    Instant to,
            @Parameter(description = "Zero-based page number", example = "0")
                    @RequestParam(defaultValue = "0")
                    int page,
            @Parameter(description = "Page size from 1 through 100", example = "20")
                    @RequestParam(defaultValue = "20")
                    int size,
            @Parameter(hidden = true) HttpServletRequest servletRequest) {

        PaymentSearchQuery query =
                new PaymentSearchQuery(merchantId(jwt), status, from, to, page, size);
        return envelope(searchPayments.handle(query), servletRequest);
    }

    @Operation(
            operationId = "createRefund",
            summary = "Yêu cầu hoàn tiền cho một payment",
            description =
                    "Kiểm tra payment thuộc merchant trong JWT, khóa và giữ phần hạn mức có thể "
                            + "hoàn, sau đó ghi refund.requested vào transactional outbox. Trả 202 "
                            + "ngay khi yêu cầu đã được lưu; Kafka tiếp tục ghi sổ hoàn tiền và "
                            + "cộng lại số dư. Gửi lại cùng Idempotency-Key và cùng body sẽ nhận "
                            + "lại kết quả cũ, không tạo refund thứ hai.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "202",
                description = "Refund mới đã được nhận, hoặc replay idempotent của yêu cầu cũ",
                useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Body/header không hợp lệ", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Thiếu hoặc sai bearer token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Token thiếu payment:write/merchant_id", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không thấy payment thuộc merchant", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Sai trạng thái, vượt hạn mức hoàn, hoặc xung đột idempotency",
                content = @Content)
    })
    @PostMapping("/{paymentId}/refunds")
    ResponseEntity<ApiResponse<RefundAcceptance>> refund(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "ID payment đã thành công cần hoàn", required = true)
                    @PathVariable
                    UUID paymentId,
            @Parameter(
                            name = IDEMPOTENCY_KEY_HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Khóa chống tạo refund trùng; tối đa 100 ký tự",
                            example = "refund-order-2026-00001-v1")
                    @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request,
            @Parameter(hidden = true) HttpServletRequest servletRequest) {

        String key = requireIdempotencyKey(idempotencyKey);
        CreateRefundResult result = createRefund.handle(
                request.toCommand(merchantId(jwt), actorId(jwt), paymentId, key));
        return ResponseEntity.status(result.responseStatus())
                .body(envelope(result.refund(), servletRequest));
    }

    @Operation(
            operationId = "createPayment",
            summary = "Nhận một payment để xử lý bất đồng bộ",
            description =
                    "Lấy merchant_id từ JWT, kiểm tra request, tạo payment ở trạng thái CREATED "
                            + "và ghi payment.created vào transactional outbox trong cùng transaction. "
                            + "Trả 202 trước khi risk/account/ledger hoàn tất. Client dùng paymentId "
                            + "trả về để gọi GET và theo dõi đến trạng thái cuối. Gửi lại cùng "
                            + "Idempotency-Key và cùng body không tạo giao dịch thứ hai.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "202",
                description = "Payment mới đã được nhận, hoặc replay idempotent của yêu cầu cũ",
                useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Body/header không hợp lệ", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Thiếu hoặc sai bearer token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Token thiếu payment:write/merchant_id", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "Trùng merchantReference hoặc xung đột Idempotency-Key",
                content = @Content)
    })
    @PostMapping
    ResponseEntity<ApiResponse<PaymentAcceptance>> create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(
                            name = IDEMPOTENCY_KEY_HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "Khóa chống tạo payment trùng; tối đa 100 ký tự",
                            example = "pay-order-2026-00001-v1")
                    @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            @Parameter(hidden = true) HttpServletRequest servletRequest) {

        String key = requireIdempotencyKey(idempotencyKey);
        CreatePaymentResult result = createPayment.handle(request.toCommand(merchantId(jwt), key));

        return ResponseEntity.status(result.responseStatus())
                .body(envelope(result.payment(), servletRequest));
    }

    @Operation(
            operationId = "getPayment",
            summary = "Đọc payment và trạng thái xử lý hiện tại",
            description =
                    "Chỉ trả payment thuộc merchant_id trong JWT. Dùng API này để polling sau khi "
                            + "POST trả 202; trạng thái có thể đang xử lý hoặc đã kết thúc như "
                            + "SUCCEEDED, FAILED, MANUAL_REVIEW_REQUIRED, PARTIALLY_REFUNDED/REFUNDED. "
                            + "API đọc này không phát Kafka event.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Chi tiết payment hiện tại", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Thiếu hoặc sai bearer token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Token thiếu payment:read/merchant_id", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Không thấy payment thuộc merchant", content = @Content)
    })
    @GetMapping("/{paymentId}")
    ApiResponse<PaymentDetail> get(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "ID nhận từ POST /api/v1/payments", required = true)
                    @PathVariable
                    UUID paymentId,
            @Parameter(hidden = true) HttpServletRequest servletRequest) {

        return envelope(getPayment.handle(paymentId, merchantId(jwt)), servletRequest);
    }

    private <T> ApiResponse<T> envelope(T data, HttpServletRequest request) {
        String correlationId = CorrelationIdFilter.current(request);
        if (!CorrelationId.isSafe(correlationId)) {
            throw new IllegalStateException("request correlation id is missing");
        }
        return new ApiResponse<>(data, new ResponseMeta(correlationId, clock.instant()));
    }

    private static UUID merchantId(Jwt jwt) {
        if (jwt == null) {
            throw new AccessDeniedException("authenticated principal is unavailable");
        }
        String claim = jwt.getClaimAsString(MERCHANT_ID_CLAIM);
        try {
            return UUID.fromString(claim);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            // Không echo lại nội dung claim. Nó là nội dung của token và có thể được cung cấp bởi một issuer không hợp lệ.
            throw new AccessDeniedException("authenticated principal has no merchant identity", invalid);
        }
    }

    private static String actorId(Jwt jwt) {
        if (jwt == null
                || jwt.getSubject() == null
                || jwt.getSubject().isBlank()
                || jwt.getSubject().length() > Refund.MAX_ACTOR_ID_LENGTH) {
            throw new AccessDeniedException("authenticated principal has no subject identity");
        }
        return jwt.getSubject();
    }

    private static String requireIdempotencyKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IdempotencyKeyRequiredException();
        }
        if (value.length() > PaymentIntake.MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    IDEMPOTENCY_KEY_HEADER
                            + " must be at most "
                            + PaymentIntake.MAX_IDEMPOTENCY_KEY_LENGTH
                            + " characters");
        }
        return value;
    }
}
