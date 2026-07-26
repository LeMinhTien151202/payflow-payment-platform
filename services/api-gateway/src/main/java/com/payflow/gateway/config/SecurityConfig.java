package com.payflow.gateway.config;

import com.payflow.gateway.web.ProblemDetailErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Edge authorization.
 *
 * <p>Deny-by-default per AGENTS.md section 8: {@code anyExchange().denyAll()} is the last rule, so a
 * new route is unreachable until someone states its required authority. Forgetting a rule produces a
 * 403, not accidental public access.
 *
 * <p>The gateway validating the token does not make downstream validation optional. Each service
 * validates the JWT again and enforces ownership itself, because the gateway cannot know which
 * merchant owns a given resource.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Scope required to read payment resources. */
    private static final String SCOPE_PAYMENT_READ = "SCOPE_payment:read";

    /** Scope required to create or mutate payment resources. */
    private static final String SCOPE_PAYMENT_WRITE = "SCOPE_payment:write";

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http, ProblemDetailErrorWriter errors) {

        return http
                // Stateless bearer-token API: there is no browser session or CSRF token to protect.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Liveness/readiness only. The full actuator surface stays private per
                        // AGENTS.md section 10.
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/payments/**")
                        .hasAuthority(SCOPE_PAYMENT_READ)
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments/**")
                        .hasAuthority(SCOPE_PAYMENT_WRITE)
                        .anyExchange().denyAll())
                // The entry point is set in both places on purpose. exceptionHandling covers a
                // request that carried no credentials at all, while oauth2ResourceServer covers a
                // token that was supplied and rejected — that failure is handled by the bearer-token
                // filter and never reaches exceptionHandling. Configuring only one leaves the other
                // path returning Spring's default empty body, so the error contract would depend on
                // whether the caller sent a token.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint((exchange, ex) -> errors.unauthenticated(exchange))
                        .accessDeniedHandler((exchange, ex) -> errors.forbidden(exchange))
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((exchange, ex) -> errors.unauthenticated(exchange))
                        .accessDeniedHandler((exchange, ex) -> errors.forbidden(exchange)))
                .build();
    }
}
