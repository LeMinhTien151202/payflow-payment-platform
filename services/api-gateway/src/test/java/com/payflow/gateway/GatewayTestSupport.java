package com.payflow.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Shared fixtures cho các bài gateway integration tests.
 *
 * <p>Giữ downstream stub và token builder để mỗi class test chỉ nêu những gì nó đang chứng minh.
 * WireMock đóng vai trò thay thế cho payment-service: việc routing và authorization của gateway phải
 * chứng minh được mà không cần service khác đang chạy, nếu không lỗi ở gateway test sẽ rất mơ hồ.
 *
 * <p>Stub được start 1 lần cho toàn bộ JVM chứ không phải từng class, vì port được công bố
 * thông qua {@link DynamicPropertySource} vào Spring context và việc restart nó sẽ làm mất hiệu lực
 * của context đã cached.
 */
abstract class GatewayTestSupport {

    /** Giá trị token mà mocked decoder phân giải thành principal chứa cả 2 payment scopes. */
    static final String TOKEN_FULL_SCOPE = "test-token-full-scope";

    /** Giá trị token phân giải thành principal chỉ chứa {@code payment:read}. */
    static final String TOKEN_READ_ONLY = "test-token-read-only";

    /** Operations identity has no merchant payment scopes. */
    static final String TOKEN_OPERATIONS = "test-token-operations";
    static final String TOKEN_SETTLEMENT_READ = "test-token-settlement-read";
    static final String TOKEN_SETTLEMENT_OPERATIONS = "test-token-settlement-operations";

    /** Giá trị token mà mocked decoder từ chối, thay thế cho token hết hạn hoặc giả mạo. */
    static final String TOKEN_INVALID = "test-token-invalid";

    static final WireMockServer PAYMENT_SERVICE_STUB =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PAYMENT_SERVICE_STUB.start();
        // Không dừng tường minh: stub sống theo vòng đời JVM cùng với các Spring context
        // đã cached và được cấu hình với port của nó. Surefire/Failsafe sẽ tear down toàn bộ JVM.
    }

    @LocalServerPort
    private int port;

    /**
     * Client được bind tới gateway thật đang chạy.
     *
     * <p>Tự khởi tạo thay vì inject: Spring Boot chỉ tự động cấu hình {@code WebTestClient}
     * cho môi trường web mock, trong khi các test này cần một HTTP hop thực sự để proxy behaviour
     * và header rewriting của gateway thực sự được chạy.
     */
    protected WebTestClient webTestClient;

    @BeforeEach
    void bindClientToRunningGateway() {
        webTestClient =
                WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port)
                        // Cao hơn mức 5s mặc định: request đầu tiên phải trả chi phí cho gateway route và security
                        // initialisation, vốn đủ chậm trên một JVM lạnh để gây ra lỗi flake nếu không nâng timeout.
                        .responseTimeout(Duration.ofSeconds(20))
                        .build();
    }

    @DynamicPropertySource
    static void downstreamUri(DynamicPropertyRegistry registry) {
        registry.add(
                "payflow.gateway.downstream.payment-service",
                () -> "http://localhost:" + PAYMENT_SERVICE_STUB.port());
        registry.add(
                "payflow.gateway.downstream.settlement-service",
                () -> "http://localhost:" + PAYMENT_SERVICE_STUB.port());
    }

    /**
     * Tạo một decoded token với các scopes cách nhau bởi khoảng trắng.
     *
     * <p>Converter mặc định của Spring Security chuyển claim {@code scope} thành các authority {@code SCOPE_},
     * khớp chính xác với những gì security configuration kiểm tra, do đó cấu trúc claim ở đây
     * phải khớp với những gì Keycloak phát hành.
     */
    static Jwt jwtWithScopes(String tokenValue, String scopes) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .header("typ", "JWT")
                .subject("service-account-payflow-service")
                .issuer("http://localhost:8180/realms/payflow")
                .audience(java.util.List.of("account"))
                .claim("scope", scopes)
                .claim("azp", "payflow-service")
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .build();
    }
}
