package com.payflow.payment.api.request;

import com.payflow.payment.application.operations.ManualReviewDecision;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** No free-form note is accepted: ADR-022 permits stable decision codes only. */
@Schema(description = "Audited decision for a Payment Saga stopped in manual review")
public record ResolveManualReviewRequest(
        @NotNull
                @Schema(
                        description = "Decision constrained by the persisted Saga step",
                        example = "RETRY_CURRENT_STEP")
                ManualReviewDecision decision,
        @NotBlank
                @Pattern(regexp = "[A-Z][A-Z0-9_]{0,99}")
                @Schema(
                        description = "Stable allowlisted incident/decision code; never a free-form note",
                        example = "OPS_VERIFIED_SAFE_RETRY")
                String decisionCode) {}
