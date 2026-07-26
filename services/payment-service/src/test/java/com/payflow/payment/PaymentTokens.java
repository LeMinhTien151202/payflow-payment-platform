package com.payflow.payment;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Token fixtures for payment-service tests.
 *
 * <p>The claim shape mirrors what the Keycloak realm in {@code infrastructure/keycloak} issues,
 * because the authority mapping under test is Spring Security's {@code scope} claim conversion. A
 * fixture that invented a different claim name would pass while production failed.
 */
public final class PaymentTokens {

    public static final UUID MERCHANT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** Resolves to a principal holding both payment scopes. */
    public static final String FULL_SCOPE = "test-token-full-scope";

    /** Resolves to a principal holding only {@code payment:read}. */
    public static final String READ_ONLY = "test-token-read-only";

    /** Resolves to a principal holding no payment scope at all. */
    public static final String NO_SCOPE = "test-token-no-scope";

    /** Rejected by the decoder, standing in for an expired or forged token. */
    public static final String INVALID = "test-token-invalid";

    private PaymentTokens() {
    }

    public static Jwt jwtWithScopes(String tokenValue, String scopes) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(tokenValue)
                .header("alg", "RS256")
                .header("typ", "JWT")
                .subject("service-account-payflow-service")
                .issuer("http://localhost:8180/realms/payflow")
                .audience(List.of("account"))
                .claim("scope", scopes)
                .claim("merchant_id", MERCHANT_ID.toString())
                .claim("azp", "payflow-service")
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .build();
    }
}
