package com.payflow.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class PayFlowJwtAuthenticationConvertersTest {

    @Test
    void keepsServiceScopesAndMapsKeycloakRealmRoles() {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("sub", "merchant-admin-subject")
                .claim("scope", "openid payment:read")
                .claim("realm_access", Map.of("roles", java.util.List.of(
                        "MERCHANT_ADMIN", "payment:read", "payment:write")))
                .build();

        var authentication = PayFlowJwtAuthenticationConverters.servlet().convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains(
                        "SCOPE_openid",
                        "SCOPE_payment:read",
                        "SCOPE_payment:write",
                        "ROLE_MERCHANT_ADMIN",
                        "ROLE_payment:read")
                .doesNotContain("SCOPE_MERCHANT_ADMIN");
    }

    @Test
    void safelyHandlesTokenWithoutRealmAccess() {
        Jwt jwt = Jwt.withTokenValue("service-token")
                .header("alg", "none")
                .claim("sub", "service-account")
                .claim("scope", "payment:write")
                .build();

        var authentication = PayFlowJwtAuthenticationConverters.servlet().convert(jwt);

        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .contains("SCOPE_payment:write")
                .noneMatch(authority -> authority.startsWith("ROLE_"));
    }
}
