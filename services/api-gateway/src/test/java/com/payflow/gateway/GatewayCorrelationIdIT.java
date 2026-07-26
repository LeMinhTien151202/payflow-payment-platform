package com.payflow.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.payflow.observability.CorrelationId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * Proves correlation id handling at the edge, per ARCHITECTURE.md section 12.
 *
 * <p>Three obligations are checked: the client's id survives the hop, a missing id is created rather
 * than left empty, and a hostile id is replaced instead of echoed. The last one matters because the
 * id reaches both the log file and the response body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayCorrelationIdIT extends GatewayTestSupport {

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void stubDecoderAndDownstream() {
        given(jwtDecoder.decode(anyString()))
                .willReturn(Mono.error(new BadJwtException("unknown token")));
        given(jwtDecoder.decode(TOKEN_READ_ONLY))
                .willReturn(Mono.just(jwtWithScopes(TOKEN_READ_ONLY, "payment:read")));

        PAYMENT_SERVICE_STUB.resetAll();
        PAYMENT_SERVICE_STUB.stubFor(
                get(urlPathMatching("/api/v1/payments.*"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"paymentId\":\"pay_1\"}")));
    }

    @Test
    @DisplayName("a supplied correlation id is echoed to the caller and forwarded downstream")
    void propagatesSuppliedCorrelationId() {
        String supplied = "trace-abc_123.9";

        webTestClient
                .get()
                .uri("/api/v1/payments/pay_1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_READ_ONLY)
                .header(CorrelationId.HEADER, supplied)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals(CorrelationId.HEADER, supplied);

        assertThat(forwardedCorrelationId()).isEqualTo(supplied);
    }

    @Test
    @DisplayName("a missing correlation id is generated and forwarded, not left empty")
    void generatesMissingCorrelationId() {
        webTestClient
                .get()
                .uri("/api/v1/payments/pay_1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_READ_ONLY)
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .exists(CorrelationId.HEADER);

        String forwarded = forwardedCorrelationId();
        assertThat(forwarded).isNotNull();
        assertThat(CorrelationId.isSafe(forwarded)).isTrue();
    }

    /**
     * A CR/LF in this header would let the caller write their own lines into the access log. The
     * gateway must substitute a clean id, and must not put the hostile value in the response either.
     */
    @Test
    @DisplayName("a correlation id containing CRLF is replaced, not echoed or forwarded")
    void replacesUnsafeCorrelationId() {
        String hostile = "abc\r\nlevel=ERROR message=payment settled";

        String echoed =
                webTestClient
                        .get()
                        .uri("/api/v1/payments/pay_1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_READ_ONLY)
                        .header(CorrelationId.HEADER, sanitizedForTransport(hostile))
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .returnResult(String.class)
                        .getResponseHeaders()
                        .getFirst(CorrelationId.HEADER);

        assertThat(echoed).isNotNull();
        assertThat(CorrelationId.isSafe(echoed)).isTrue();
        assertThat(echoed).isNotEqualTo(sanitizedForTransport(hostile));
        assertThat(CorrelationId.isSafe(forwardedCorrelationId())).isTrue();
    }

    @Test
    @DisplayName("an authentication failure still carries a correlation id for support lookup")
    void unauthenticatedResponseCarriesCorrelationId() {
        String supplied = "trace-unauth-1";

        webTestClient
                .get()
                .uri("/api/v1/payments/pay_1")
                .header(CorrelationId.HEADER, supplied)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .valueEquals(CorrelationId.HEADER, supplied)
                .expectBody()
                .jsonPath("$.correlationId")
                .isEqualTo(supplied);
    }

    /**
     * An HTTP client will not transmit a raw CR/LF in a header value, so the test sends a value that
     * is transportable yet still unsafe by PayFlow's rule. The raw-CRLF case is covered as a unit
     * test in {@code CorrelationIdTest}; what this proves is that the gateway applies the same rule.
     */
    private static String sanitizedForTransport(String hostile) {
        return hostile.replace("\r", "").replace("\n", "");
    }

    private String forwardedCorrelationId() {
        List<LoggedRequest> requests =
                PAYMENT_SERVICE_STUB.findAll(getRequestedFor(urlPathMatching("/api/v1/payments.*")));
        assertThat(requests).hasSize(1);
        return requests.getFirst().getHeader(CorrelationId.HEADER);
    }
}
