package com.payflow.payment.infrastructure.web;

import static com.payflow.payment.PaymentTokens.FULL_SCOPE;
import static com.payflow.payment.PaymentTokens.INVALID;
import static com.payflow.payment.PaymentTokens.NO_SCOPE;
import static com.payflow.payment.PaymentTokens.READ_ONLY;
import static com.payflow.payment.PaymentTokens.jwtWithScopes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payflow.observability.CorrelationId;
import com.payflow.payment.PaymentTokens;
import com.payflow.payment.application.exception.PaymentNotFoundException;
import com.payflow.payment.application.handler.CreatePaymentHandler;
import com.payflow.payment.application.handler.CreateRefundHandler;
import com.payflow.payment.application.handler.GetPaymentHandler;
import com.payflow.payment.infrastructure.security.SecurityConfig;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Proves payment-service enforces its own authorization and speaks the PayFlow error contract.
 *
 * <p>A web slice test, not a full context: no database is involved, so this runs in Surefire without
 * Docker and still covers the whole filter chain including security and the exception handler.
 *
 * <p>Security runs before request dispatch, so an unauthorized caller gets 401 or 403 while an
 * authorized caller reaches the tenant-scoped use case and may get 404.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class PaymentErrorContractTest {

    private static final UUID UNKNOWN_PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CreatePaymentHandler createPaymentHandler;

    @MockitoBean
    private CreateRefundHandler createRefundHandler;

    @MockitoBean
    private GetPaymentHandler getPaymentHandler;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void stubDecoder() {
        // willX(...).given(mock) rather than given(mock.call()): the latter really invokes the mock
        // while stubbing, so the catch-all below would throw before the specific stubs registered.
        //
        // The catch-all goes first because Mockito answers with the last matching stub. An
        // unstubbed token would otherwise decode to null and surface as a 500, hiding whichever
        // authorization rule the test meant to exercise.
        willThrow(new BadJwtException("unknown token")).given(jwtDecoder).decode(anyString());
        willReturn(jwtWithScopes(FULL_SCOPE, "payment:read payment:write"))
                .given(jwtDecoder)
                .decode(FULL_SCOPE);
        willReturn(jwtWithScopes(READ_ONLY, "payment:read")).given(jwtDecoder).decode(READ_ONLY);
        willReturn(jwtWithScopes(NO_SCOPE, "openid")).given(jwtDecoder).decode(NO_SCOPE);
        willThrow(new BadJwtException("signature mismatch")).given(jwtDecoder).decode(INVALID);
        willThrow(new PaymentNotFoundException(UNKNOWN_PAYMENT_ID, PaymentTokens.MERCHANT_ID))
                .given(getPaymentHandler)
                .handle(UNKNOWN_PAYMENT_ID, PaymentTokens.MERCHANT_ID);
    }

    @Test
    @DisplayName("no token yields 401 Problem Details, even though the gateway already checked one")
    void missingTokenIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + UNKNOWN_PAYMENT_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    @DisplayName("a rejected token yields 401 with the same body shape as a missing token")
    void invalidTokenIsUnauthenticated() throws Exception {
        mockMvc.perform(
                        get("/api/v1/payments/" + UNKNOWN_PAYMENT_ID)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + INVALID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("a valid token without a payment scope yields 403")
    void tokenWithoutPaymentScopeIsForbidden() throws Exception {
        mockMvc.perform(
                        get("/api/v1/payments/" + UNKNOWN_PAYMENT_ID)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + NO_SCOPE))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    @Test
    @DisplayName("payment:read cannot create a payment")
    void readScopeCannotWrite() throws Exception {
        mockMvc.perform(
                        post("/api/v1/payments")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + READ_ONLY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    /** The 404 proves authorization passed before tenant-scoped resource lookup. */
    @Test
    @DisplayName("an authorized caller reaches the use case and an invisible payment yields 404")
    void authorizedCallerReachesDispatch() throws Exception {
        mockMvc.perform(
                        get("/api/v1/payments/" + UNKNOWN_PAYMENT_ID)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }

    @Test
    @DisplayName("a path with no explicit rule is denied even with a fully scoped token")
    void unmappedPathIsDeniedByDefault() throws Exception {
        mockMvc.perform(
                        get("/some/unconfigured/path")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
    }

    // Health being publicly reachable is asserted in PaymentServiceFoundationIT instead: actuator
    // endpoints are not part of the MVC slice, so here /actuator/health would 404 for a reason that
    // has nothing to do with the security rule under test.

    @Test
    @DisplayName("a correlation id supplied by the gateway is echoed back")
    void echoesSuppliedCorrelationId() throws Exception {
        String supplied = "trace-from-gateway-1";

        mockMvc.perform(
                        get("/api/v1/payments/" + UNKNOWN_PAYMENT_ID)
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE)
                                .header(CorrelationId.HEADER, supplied))
                .andExpect(header().string(CorrelationId.HEADER, supplied))
                .andExpect(jsonPath("$.correlationId").value(supplied));
    }

    @Test
    @DisplayName("a request arriving without a correlation id still gets one")
    void generatesMissingCorrelationId() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/payments/" + UNKNOWN_PAYMENT_ID)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE))
                        .andExpect(header().exists(CorrelationId.HEADER))
                        .andReturn();

        String generated = result.getResponse().getHeader(CorrelationId.HEADER);
        assertThat(generated).isNotNull();
        assertThat(CorrelationId.isSafe(generated)).isTrue();
    }

    /** A client-supplied id reaches both the log and the response body, so it must be filtered. */
    @Test
    @DisplayName("an unsafe correlation id is replaced rather than reflected")
    void replacesUnsafeCorrelationId() throws Exception {
        String hostile = "abc level=ERROR message=payment settled";

        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/payments/" + UNKNOWN_PAYMENT_ID)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + FULL_SCOPE)
                                        .header(CorrelationId.HEADER, hostile))
                        .andReturn();

        String echoed = result.getResponse().getHeader(CorrelationId.HEADER);
        assertThat(echoed).isNotEqualTo(hostile);
        assertThat(CorrelationId.isSafe(echoed)).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("level=ERROR");
    }

    /**
     * AGENTS.md section 7: an error response must never carry a stack trace, SQL, or internal type
     * name. Asserted on the 401 body because that is the one an unauthenticated stranger can reach.
     */
    @Test
    @DisplayName("an error body carries no stack trace or internal detail")
    void errorBodyLeaksNothingInternal() throws Exception {
        String body =
                mockMvc.perform(
                                get("/api/v1/payments/" + UNKNOWN_PAYMENT_ID)
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + INVALID))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("org.springframework")
                .doesNotContain("com.payflow")
                .doesNotContain("signature mismatch")
                .doesNotContain("at ");
    }
}
