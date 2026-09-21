package com.payflow.notification.infrastructure.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Notification exposes no business HTTP API: health is public and every other route is denied.
 *
 * <p>Servlet stacks only. A filter chain has nothing to apply to without one, and the
 * {@code JwtDecoder} it needs comes from an auto-configuration that is itself servlet-conditional,
 * so in a non-web context ({@code WebEnvironment.NONE}) this class would ask for a bean that was
 * never created and fail the whole context.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers("/api/v1/operations/webhooks/**")
                        .hasAuthority("SCOPE_webhook:retry")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(
                        com.payflow.security.jwt.PayFlowJwtAuthenticationConverters.servlet())))
                .build();
    }
}
