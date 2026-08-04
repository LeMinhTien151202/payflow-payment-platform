package com.payflow.payment.api;

import com.payflow.observability.CorrelationId;
import com.payflow.payment.api.request.ResolveManualReviewRequest;
import com.payflow.payment.api.response.ApiResponse;
import com.payflow.payment.api.response.ResponseMeta;
import com.payflow.payment.application.handler.ResolveManualReviewHandler;
import com.payflow.payment.application.operations.ManualReviewResolutionResult;
import com.payflow.payment.application.operations.ResolveManualReviewCommand;
import com.payflow.payment.infrastructure.web.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Privileged Payment operations API; isolated from merchant-owned routes and scopes. */
@RestController
@RequestMapping("/api/v1/operations/payments")
@Tag(name = "Payment Operations", description = "Audited recovery actions for stopped Payment Sagas")
@SecurityRequirement(name = PaymentOpenApiConfig.BEARER_AUTH)
public class OperationsController {

    private final ResolveManualReviewHandler resolveManualReview;
    private final Clock clock;

    public OperationsController(ResolveManualReviewHandler resolveManualReview, Clock clock) {
        this.resolveManualReview = resolveManualReview;
        this.clock = clock;
    }

    @Operation(
            operationId = "resolvePaymentManualReview",
            summary = "Resolve one Payment Saga manual-review item",
            description =
                    "Requires operations:write. The handler locks through optimistic versions and commits "
                            + "Payment/Saga state, the next outbox command and a typed append-only audit row "
                            + "in one transaction. Risk review accepts APPROVE_RISK/REJECT_RISK; financial "
                            + "steps accept RETRY_CURRENT_STEP only.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200", description = "Resolution committed", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400", description = "Invalid decision or stable code", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401", description = "Missing or invalid bearer token", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403", description = "Token lacks operations:write", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404", description = "Manual-review work item not found", content = @Content),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409", description = "Decision is unsafe for current persisted facts", content = @Content)
    })
    @PostMapping("/{paymentId}/manual-review/resolve")
    ApiResponse<ManualReviewResolutionResult> resolve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID paymentId,
            @Valid @RequestBody ResolveManualReviewRequest request,
            HttpServletRequest servletRequest) {
        String correlationId = CorrelationIdFilter.current(servletRequest);
        if (!CorrelationId.isSafe(correlationId)) {
            throw new IllegalStateException("request correlation id is missing");
        }
        var result = resolveManualReview.handle(new ResolveManualReviewCommand(
                paymentId, request.decision(), request.decisionCode(), actorSubject(jwt), correlationId));
        return new ApiResponse<>(result, new ResponseMeta(correlationId, clock.instant()));
    }

    private static String actorSubject(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank()
                || jwt.getSubject().length() > 255) {
            throw new AccessDeniedException("authenticated principal has no subject identity");
        }
        return jwt.getSubject();
    }
}
