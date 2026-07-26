package com.payflow.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * Proves the edge authorization rules of ARCHITECTURE.md and AGENTS.md section 8.
 *
 * <p>The JWT decoder is mocked rather than backed by a live Keycloak. What is under test is the
 * authorization decision — scope to route mapping, deny-by-default, and the error contract — not
 * Nimbus signature verification, which is Spring Security's own tested code. Mocking the decoder
 * also keeps the suite runnable without Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewaySecurityIT extends GatewayTestSupport {

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void stubDecoderAndDownstream() {
        // Any token this suite did not deliberately mint is invalid. Without this catch-all an
        // unstubbed call would return null and surface as a 500, masking the real assertion.
        given(jwtDecoder.decode(anyString()))
                .willReturn(Mono.error(new BadJwtException("unknown token")));
        given(jwtDecoder.decode(TOKEN_FULL_SCOPE))
                .willReturn(Mono.just(jwtWithScopes(TOKEN_FULL_SCOPE, "payment:read payment:write")));
        given(jwtDecoder.decode(TOKEN_READ_ONLY))
                .willReturn(Mono.just(jwtWithScopes(TOKEN_READ_ONLY, "payment:read")));
        given(jwtDecoder.decode(TOKEN_INVALID))
                .willReturn(Mono.error(new BadJwtException("signature mismatch")));

        PAYMENT_SERVICE_STUB.resetAll();
        PAYMENT_SERVICE_STUB.stubFor(
                get(urlPathMatching("/api/v1/payments.*"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"paymentId\":\"pay_1\"}")));
        PAYMENT_SERVICE_STUB.stubFor(
                post(urlPathMatching("/api/v1/payments.*"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"paymentId\":\"pay_2\"}")));
    }

    @Test
    @DisplayName("no token yields 401 as Problem Details with a stable code")
    void missingTokenIsUnauthenticated() {
        webTestClient
                .get()
                .uri("/api/v1/payments/pay_1")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTH_UNAUTHENTICATED")
                .jsonPath("$.status")
                .isEqualTo(401)
                .jsonPath("$.correlationId")
                .exists();
    }

    @Test
    @DisplayName("a rejected token yields 401, never reaching the downstream service")
    void invalidTokenIsUnauthenticated() {
        webTestClient
                .get()
                .uri("/api/v1/payments/pay_1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_INVALID)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTH_UNAUTHENTICATED");

        // The point of the edge check: an unauthenticated request must cost the downstream nothing.
        PAYMENT_SERVICE_STUB.verify(0, getRequestedFor(urlPathMatching("/api/v1/payments.*")));
    }

    @Test
    @DisplayName("payment:read is sufficient to read a payment")
    void readScopeReachesDownstream() {
        webTestClient
                .get()
                .uri("/api/v1/payments/pay_1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_READ_ONLY)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.paymentId")
                .isEqualTo("pay_1");
    }

    @Test
    @DisplayName("payment:read alone cannot create a payment")
    void readScopeCannotWrite() {
        webTestClient
                .post()
                .uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_READ_ONLY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTH_FORBIDDEN");

        PAYMENT_SERVICE_STUB.verify(0, postRequestedFor(urlPathMatching("/api/v1/payments.*")));
    }

    @Test
    @DisplayName("payment:write allows creating a payment")
    void writeScopeReachesDownstream() {
        webTestClient
                .post()
                .uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_FULL_SCOPE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isCreated();
    }

    /**
     * The regression this guards against: adding a route without adding an authorization rule. With
     * {@code anyExchange().denyAll()} last, the omission produces a 403 instead of silently exposing
     * the route to any authenticated caller.
     */
    @Test
    @DisplayName("a path with no explicit rule is denied even with a fully scoped token")
    void unmappedPathIsDeniedByDefault() {
        webTestClient
                .get()
                .uri("/api/v1/something-nobody-configured")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_FULL_SCOPE)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTH_FORBIDDEN");
    }

    @Test
    @DisplayName("internal endpoints are not reachable through the public edge")
    void internalPathsAreNotExposed() {
        webTestClient
                .get()
                .uri("/internal/v1/payments/pay_1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_FULL_SCOPE)
                .exchange()
                .expectStatus()
                .isForbidden();

        PAYMENT_SERVICE_STUB.verify(0, getRequestedFor(urlPathMatching("/internal.*")));
    }

    @Test
    @DisplayName("health is reachable without a token so orchestrators can probe it")
    void healthIsPublic() {
        webTestClient.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    /**
     * An error body must not describe the authorization model. Naming the missing scope would tell an
     * attacker exactly which scope to go after, so the message stays generic.
     */
    @Test
    @DisplayName("a 403 body does not disclose which scope was missing")
    void forbiddenBodyDoesNotLeakRequiredScope() {
        webTestClient
                .post()
                .uri("/api/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_READ_ONLY)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(String.class)
                .value(
                        body -> {
                            org.assertj.core.api.Assertions.assertThat(body)
                                    .doesNotContain("payment:write")
                                    .doesNotContain("SCOPE_")
                                    .doesNotContain("Exception");
                        });
    }
}
