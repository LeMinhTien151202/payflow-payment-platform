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
 * Shared fixtures for the gateway integration tests.
 *
 * <p>Holds the downstream stub and the token builders so each test class states only what it is
 * proving. WireMock stands in for payment-service: the gateway's own routing and authorization must
 * be provable without another service running, otherwise a gateway test failure would be ambiguous.
 *
 * <p>The stub is started once for the whole JVM rather than per class, because the port is published
 * through {@link DynamicPropertySource} into the Spring context and restarting it would invalidate
 * an already-cached context.
 */
abstract class GatewayTestSupport {

    /** Token value the mocked decoder resolves to a principal holding both payment scopes. */
    static final String TOKEN_FULL_SCOPE = "test-token-full-scope";

    /** Token value resolving to a principal holding only {@code payment:read}. */
    static final String TOKEN_READ_ONLY = "test-token-read-only";

    /** Token value the mocked decoder rejects, standing in for an expired or forged token. */
    static final String TOKEN_INVALID = "test-token-invalid";

    static final WireMockServer PAYMENT_SERVICE_STUB =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        PAYMENT_SERVICE_STUB.start();
        // Not stopped explicitly: the stub lives for the JVM's lifetime alongside the cached Spring
        // contexts that were configured with its port. Surefire/Failsafe tear down the whole JVM.
    }

    @LocalServerPort
    private int port;

    /**
     * Client bound to the real running gateway.
     *
     * <p>Built by hand rather than injected: Spring Boot only auto-configures a {@code WebTestClient}
     * for a mock web environment, and these tests need a genuine HTTP hop so that the gateway's proxy
     * behaviour and header rewriting are actually exercised.
     */
    protected WebTestClient webTestClient;

    @BeforeEach
    void bindClientToRunningGateway() {
        webTestClient =
                WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port)
                        // Above the default 5s: the first request pays gateway route and security
                        // initialisation, which is slow enough on a cold JVM to flake otherwise.
                        .responseTimeout(Duration.ofSeconds(20))
                        .build();
    }

    @DynamicPropertySource
    static void downstreamUri(DynamicPropertyRegistry registry) {
        registry.add(
                "payflow.gateway.downstream.payment-service",
                () -> "http://localhost:" + PAYMENT_SERVICE_STUB.port());
    }

    /**
     * Builds a decoded token with the given space-delimited scopes.
     *
     * <p>Spring Security's default converter turns the {@code scope} claim into {@code SCOPE_}
     * authorities, which is exactly what the security configuration asserts on, so the claim shape
     * here has to match what Keycloak issues.
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
