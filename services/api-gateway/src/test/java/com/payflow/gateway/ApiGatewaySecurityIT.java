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
 * Chứng minh các quy tắc phân quyền ở tầng Edge theo ARCHITECTURE.md và AGENTS.md phần 8.
 *
 * <p>JWT decoder được mock thay vì kết nối tới Keycloak thật. Thứ được kiểm thử ở đây là
 * quyết định phân quyền — ánh xạ scope tới route, deny-by-default, và error contract — chứ không phải
 * việc kiểm tra chữ ký Nimbus (vốn là code đã được test của Spring Security). Việc mock decoder
 * cũng giúp test suite có thể chạy được mà không cần Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewaySecurityIT extends GatewayTestSupport {

    @MockitoBean
    private ReactiveJwtDecoder jwtDecoder;

    @BeforeEach
    void stubDecoderAndDownstream() {
        // Bất kỳ token nào mà test suite này không chủ động tạo ra đều là không hợp lệ. Nếu không có catch-all này,
        // một call không được stub sẽ trả về null và xuất hiện dưới dạng lỗi 500, làm che mất assertion thực tế.
        given(jwtDecoder.decode(anyString()))
                .willReturn(Mono.error(new BadJwtException("unknown token")));
        given(jwtDecoder.decode(TOKEN_FULL_SCOPE))
                .willReturn(Mono.just(jwtWithScopes(TOKEN_FULL_SCOPE, "payment:read payment:write")));
        given(jwtDecoder.decode(TOKEN_READ_ONLY))
                .willReturn(Mono.just(jwtWithScopes(TOKEN_READ_ONLY, "payment:read")));
        given(jwtDecoder.decode(TOKEN_OPERATIONS))
                .willReturn(Mono.just(jwtWithScopes(TOKEN_OPERATIONS, "operations:write")));
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
        PAYMENT_SERVICE_STUB.stubFor(
                post(urlPathMatching("/api/v1/operations/payments.*"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"status\":\"RUNNING\"}")));
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

        // Điểm mấu chốt của việc kiểm tra ở tầng edge: request chưa xác thực phải không làm tốn chi phí của downstream service.
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

    @Test
    @DisplayName("operations scope reaches operations route without merchant scopes")
    void operationsScopeReachesOperationsRoute() {
        webTestClient
                .post()
                .uri("/api/v1/operations/payments/pay_1/manual-review/resolve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_OPERATIONS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("merchant payment scopes cannot reach operations route")
    void merchantScopeCannotReachOperationsRoute() {
        webTestClient
                .post()
                .uri("/api/v1/operations/payments/pay_1/manual-review/resolve")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN_FULL_SCOPE)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("AUTH_FORBIDDEN");

        PAYMENT_SERVICE_STUB.verify(
                0, postRequestedFor(urlPathMatching("/api/v1/operations/payments.*")));
    }

    /**
     * Trường hợp regression mà test này bảo vệ: thêm một route mới mà quên thêm quy tắc phân quyền. Với
     * quy tắc {@code anyExchange().denyAll()} đứng cuối, việc bỏ sót sẽ tạo ra lỗi 403 thay vì âm thầm mở
     * route cho bất kỳ caller nào đã được xác thực.
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
     * Lỗi trả về tuyệt đối không được mô tả chi tiết mô hình phân quyền. Việc gọi tên scope bị thiếu sẽ cho
     * kẻ tấn công biết chính xác scope nào cần nhắm tới, do đó thông điệp phải giữ dạng tổng quát.
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
