package com.payflow.payment.api;

import static com.payflow.payment.PaymentTokens.FULL_SCOPE;
import static com.payflow.payment.PaymentTokens.MERCHANT_ID;
import static com.payflow.payment.PaymentTokens.jwtWithScopes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payflow.payment.application.CreatePaymentResult;
import com.payflow.payment.application.PaymentAcceptance;
import com.payflow.payment.application.PaymentDetail;
import com.payflow.payment.application.command.CreatePaymentCommand;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.handler.CreatePaymentHandler;
import com.payflow.payment.application.handler.GetPaymentHandler;
import com.payflow.payment.domain.model.PaymentStatus;
import com.payflow.payment.infrastructure.security.SecurityConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Docker-free proof of the public payment contract and JWT-derived merchant ownership. */
@WebMvcTest(PaymentController.class)
@Import(SecurityConfig.class)
class PaymentControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-26T09:15:00Z");
    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID CUSTOMER_ID =
            UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final String KEY = "d290f1ee-6c54-4b01-90e6-d701748f0851";

    private static final String VALID_REQUEST =
            """
            {
              "merchantReference": "ORDER-2026-00001",
              "customerId": "3beff442-7f10-4504-aab4-12d985cf3e95",
              "sourceAccountId": "039bedb6-b2d6-47df-aa25-2035e39136a3",
              "amount": 500000,
              "currency": "VND",
              "description": "Thanh toán đơn hàng",
              "metadata": {"orderId": "ORDER-2026-00001"}
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CreatePaymentHandler createPayment;

    @MockitoBean
    private GetPaymentHandler getPayment;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void setUp() {
        willReturn(jwtWithScopes(FULL_SCOPE, "payment:read payment:write"))
                .given(jwtDecoder)
                .decode(FULL_SCOPE);
        given(clock.instant()).willReturn(NOW);
    }

    @Test
    @DisplayName("create returns 202 envelope and takes merchant identity from the JWT")
    void createsPaymentForAuthenticatedMerchant() throws Exception {
        PaymentAcceptance acceptance = acceptance();
        given(createPayment.handle(any())).willReturn(new CreatePaymentResult.Accepted(acceptance));

        mockMvc.perform(
                        post("/api/v1/payments")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE)
                                .header(PaymentController.IDEMPOTENCY_KEY_HEADER, KEY)
                                .header("X-Correlation-Id", "api-create-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.meta.correlationId").value("api-create-1"))
                .andExpect(jsonPath("$.meta.timestamp").value(NOW.toString()));

        ArgumentCaptor<CreatePaymentCommand> command =
                ArgumentCaptor.forClass(CreatePaymentCommand.class);
        verify(createPayment).handle(command.capture());
        assertThat(command.getValue().merchantId()).isEqualTo(MERCHANT_ID);
        assertThat(command.getValue().idempotencyKey()).isEqualTo(KEY);
        assertThat(command.getValue().customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(command.getValue().sourceAccountId()).isEqualTo(ACCOUNT_ID);
    }

    @Test
    @DisplayName("missing Idempotency-Key has its own stable error code")
    void requiresIdempotencyKey() throws Exception {
        mockMvc.perform(
                        post("/api/v1/payments")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("same key with a different request is a 409, not an internal error")
    void mapsIdempotencyConflict() throws Exception {
        given(createPayment.handle(any()))
                .willThrow(new IdempotencyConflictException("merchant:POST /api/v1/payments", KEY));

        mockMvc.perform(
                        post("/api/v1/payments")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE)
                                .header(PaymentController.IDEMPOTENCY_KEY_HEADER, KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_REQUEST))
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));
    }

    @Test
    @DisplayName("invalid money is rejected at the boundary with field errors")
    void validatesMoneyAtBoundary() throws Exception {
        String invalid = VALID_REQUEST.replace("500000", "0.00001");

        mockMvc.perform(
                        post("/api/v1/payments")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE)
                                .header(PaymentController.IDEMPOTENCY_KEY_HEADER, KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));
    }

    @Test
    @DisplayName("a token without merchant_id cannot choose a merchant in the body")
    void refusesTokenWithoutMerchantIdentity() throws Exception {
        String token = "token-without-merchant";
        willReturn(jwtWithoutMerchant(token)).given(jwtDecoder).decode(token);

        mockMvc.perform(
                        post("/api/v1/payments")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .header(PaymentController.IDEMPOTENCY_KEY_HEADER, KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_REQUEST))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("get passes both payment id and authenticated merchant to the use case")
    void getsOnlyWithinAuthenticatedMerchant() throws Exception {
        PaymentDetail detail =
                new PaymentDetail(
                        PAYMENT_ID,
                        MERCHANT_ID,
                        "ORDER-2026-00001",
                        CUSTOMER_ID,
                        ACCOUNT_ID,
                        new BigDecimal("500000.0000"),
                        "VND",
                        PaymentStatus.MANUAL_REVIEW_REQUIRED,
                        "Thanh toán đơn hàng",
                        Map.of("orderId", "ORDER-2026-00001"),
                        NOW,
                        NOW);
        given(getPayment.handle(PAYMENT_ID, MERCHANT_ID)).willReturn(detail);

        mockMvc.perform(
                        get("/api/v1/payments/" + PAYMENT_ID)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE)
                                .header("X-Correlation-Id", "api-get-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(PAYMENT_ID.toString()))
                .andExpect(jsonPath("$.data.merchantId").value(MERCHANT_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.meta.correlationId").value("api-get-1"));

        verify(getPayment).handle(PAYMENT_ID, MERCHANT_ID);
    }

    private static PaymentAcceptance acceptance() {
        return new PaymentAcceptance(
                PAYMENT_ID,
                PaymentStatus.CREATED,
                new BigDecimal("500000.0000"),
                "VND",
                NOW);
    }

    private static Jwt jwtWithoutMerchant(String tokenValue) {
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .subject("merchant-user-with-broken-mapping")
                .issuer("http://localhost:8180/realms/payflow")
                .audience(List.of("account"))
                .claim("scope", "payment:read payment:write")
                .issuedAt(NOW)
                .expiresAt(NOW.plus(15, ChronoUnit.MINUTES))
                .build();
    }
}
