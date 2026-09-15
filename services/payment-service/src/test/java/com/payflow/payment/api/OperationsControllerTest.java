package com.payflow.payment.api;

import static com.payflow.payment.PaymentTokens.jwtWithScopes;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payflow.payment.application.handler.ResolveManualReviewHandler;
import com.payflow.payment.application.handler.SearchManualReviewsHandler;
import com.payflow.payment.application.operations.ManualReviewItem;
import com.payflow.payment.application.operations.ManualReviewSearchResult;
import com.payflow.payment.application.operations.ManualReviewResolutionResult;
import com.payflow.payment.application.operations.ResolveManualReviewCommand;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.infrastructure.security.SecurityConfig;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OperationsController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class OperationsControllerTest {

    private static final String OPS_TOKEN = "test-token-operations";
    private static final String MERCHANT_TOKEN = "test-token-merchant";
    private static final UUID PAYMENT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID EVENT_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean ResolveManualReviewHandler handler;
    @MockitoBean SearchManualReviewsHandler searchHandler;
    @MockitoBean Clock clock;

    @BeforeEach
    void setUp() {
        given(jwtDecoder.decode(OPS_TOKEN))
                .willReturn(jwtWithScopes(OPS_TOKEN, "operations:write"));
        given(jwtDecoder.decode(MERCHANT_TOKEN))
                .willReturn(jwtWithScopes(MERCHANT_TOKEN, "payment:read payment:write"));
        given(clock.instant()).willReturn(NOW);
    }

    @Test
    void operationsScopeResolvesAndActorComesFromJwtSubject() throws Exception {
        given(handler.handle(any())).willReturn(new ManualReviewResolutionResult(
                PAYMENT_ID, PaymentStatus.PROCESSING, PaymentSagaStatus.RUNNING,
                PaymentSagaStep.CAPTURE_FUNDS, EVENT_ID, NOW));

        mockMvc.perform(post("/api/v1/operations/payments/{id}/manual-review/resolve", PAYMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + OPS_TOKEN)
                        .header("X-Correlation-Id", "ops-api-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"RETRY_CURRENT_STEP","decisionCode":"OPS_VERIFIED_SAFE_RETRY"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data.commandEventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.meta.correlationId").value("ops-api-1"));

        ArgumentCaptor<ResolveManualReviewCommand> command =
                ArgumentCaptor.forClass(ResolveManualReviewCommand.class);
        verify(handler).handle(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().actorSubject())
                .isEqualTo("service-account-payflow-service");
    }

    @Test
    void operationsCanListTheOldestManualReviewWork() throws Exception {
        given(searchHandler.handle(0, 20)).willReturn(new ManualReviewSearchResult(
                List.of(new ManualReviewItem(
                        PAYMENT_ID,
                        UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                        new BigDecimal("500000.0000"),
                        "VND",
                        PaymentSagaStep.RISK_ASSESSMENT,
                        0,
                        "RISK_REVIEW_REQUIRED",
                        null,
                        null,
                        NOW.minusSeconds(60))),
                0,
                20,
                1,
                1));

        mockMvc.perform(get("/api/v1/operations/payments/manual-review")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + OPS_TOKEN)
                        .header("X-Correlation-Id", "ops-list-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].currentStep").value("RISK_ASSESSMENT"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.meta.correlationId").value("ops-list-1"));
    }

    @Test
    void merchantPaymentScopesCannotCallOperationsRoute() throws Exception {
        mockMvc.perform(post("/api/v1/operations/payments/{id}/manual-review/resolve", PAYMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MERCHANT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"RETRY_CURRENT_STEP","decisionCode":"OPS_VERIFIED_SAFE_RETRY"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    void freeFormDecisionCodeIsRejectedBeforeHandler() throws Exception {
        mockMvc.perform(post("/api/v1/operations/payments/{id}/manual-review/resolve", PAYMENT_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + OPS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"RETRY_CURRENT_STEP","decisionCode":"looks safe to me"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
    }
}
