package com.payflow.security.jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Builds the common JWT authentication mapping used at the edge and by business services.
 *
 * <p>Client-credential tokens keep using their standard {@code scope} claim. Human users receive
 * composite realm roles from Keycloak. A realm role such as {@code payment:read} is therefore also
 * exposed as {@code SCOPE_payment:read}, while persona roles remain available as
 * {@code ROLE_MERCHANT_ADMIN}, {@code ROLE_MERCHANT_USER}, or {@code ROLE_OPERATIONS}.
 */
public final class PayFlowJwtAuthenticationConverters {

    private PayFlowJwtAuthenticationConverters() {}

    public static JwtAuthenticationConverter servlet() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new PayFlowGrantedAuthoritiesConverter());
        return converter;
    }

    /** Returns effective fine-grained permissions from both OAuth scopes and Keycloak realm roles. */
    public static Set<String> scopes(Jwt jwt) {
        Set<String> result = new LinkedHashSet<>();
        String scopeClaim = jwt.getClaimAsString("scope");
        if (scopeClaim != null) {
            for (String scope : scopeClaim.split("\\s+")) {
                if (!scope.isBlank()) {
                    result.add(scope);
                }
            }
        }
        Object realmAccessClaim = jwt.getClaim("realm_access");
        if (realmAccessClaim instanceof Map<?, ?> realmAccess
                && realmAccess.get("roles") instanceof Collection<?> roles) {
            for (Object candidate : roles) {
                if (candidate instanceof String role && role.contains(":")) {
                    result.add(role);
                }
            }
        }
        return Set.copyOf(result);
    }

    private static final class PayFlowGrantedAuthoritiesConverter
            implements Converter<Jwt, Collection<GrantedAuthority>> {

        private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

        @Override
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopes.convert(jwt));
            Object realmAccessClaim = jwt.getClaim("realm_access");
            if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
                return authorities;
            }
            Object rolesClaim = realmAccess.get("roles");
            if (!(rolesClaim instanceof Collection<?> roles)) {
                return authorities;
            }
            for (Object candidate : roles) {
                if (!(candidate instanceof String role) || role.isBlank()) {
                    continue;
                }
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                if (role.contains(":")) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + role));
                }
            }
            return authorities;
        }
    }
}
