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
import com.payflow.payment.application.RefundAcceptance;
import com.payflow.payment.application.handler.CreatePaymentHandler;
import com.payflow.payment.application.handler.CreateRefundHandler;
import com.payflow.payment.application.handler.GetPaymentHandler;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.Refund;
import com.payflow.payment.infrastructure.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
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
import org.springframework.web.bind.annotation.RestController;

/** Public payment API. Authentication context is converted here; business work stays in handlers. */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String MERCHANT_ID_CLAIM = "merchant_id";

    private final CreatePaymentHandler createPayment;
    private final CreateRefundHandler createRefund;
    private final GetPaymentHandler getPayment;
    private final Clock clock;

    public PaymentController(
            CreatePaymentHandler createPayment,
            CreateRefundHandler createRefund,
            GetPaymentHandler getPayment,
            Clock clock) {
        this.createPayment = createPayment;
        this.createRefund = createRefund;
        this.getPayment = getPayment;
        this.clock = clock;
    }

    @PostMapping("/{paymentId}/refunds")
    ResponseEntity<ApiResponse<RefundAcceptance>> refund(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paymentId,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request,
            HttpServletRequest servletRequest) {

        String key = requireIdempotencyKey(idempotencyKey);
        CreateRefundResult result = createRefund.handle(
                request.toCommand(merchantId(jwt), actorId(jwt), paymentId, key));
        return ResponseEntity.status(result.responseStatus())
                .body(envelope(result.refund(), servletRequest));
    }

    @PostMapping
    ResponseEntity<ApiResponse<PaymentAcceptance>> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request,
            HttpServletRequest servletRequest) {

        String key = requireIdempotencyKey(idempotencyKey);
        CreatePaymentResult result = createPayment.handle(request.toCommand(merchantId(jwt), key));

        return ResponseEntity.status(result.responseStatus())
                .body(envelope(result.payment(), servletRequest));
    }

    @GetMapping("/{paymentId}")
    ApiResponse<PaymentDetail> get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paymentId,
            HttpServletRequest servletRequest) {

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
            // Do not echo the claim. It is token content and may have been supplied by an invalid issuer.
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
