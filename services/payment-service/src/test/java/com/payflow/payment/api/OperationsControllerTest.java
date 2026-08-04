package com.payflow.payment.api;

import static com.payflow.payment.PaymentTokens.jwtWithScopes;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payflow.payment.application.handler.ResolveManualReviewHandler;
import com.payflow.payment.application.operations.ManualReviewResolutionResult;
import com.payflow.payment.application.operations.ResolveManualReviewCommand;
import com.payflow.payment.domain.model.PaymentSagaStatus;
import com.payflow.payment.domain.model.PaymentSagaStep;
import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.infrastructure.security.SecurityConfig;
import java.time.Clock;
import java.time.Instant;
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
