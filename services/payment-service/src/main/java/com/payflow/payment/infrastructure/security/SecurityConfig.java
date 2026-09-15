package com.payflow.payment.infrastructure.security;

import com.payflow.error.PayFlowErrorCode;
import com.payflow.error.ProblemDetails;
import com.payflow.payment.infrastructure.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * Service-level authorization.
 *
 * <p>The gateway already validated the token. This service validates it again because AGENTS.md
 * section 8 forbids trusting an identity asserted by an upstream hop: a caller inside the network
 * could reach this service directly.
 *
 * <p>Deny-by-default. Phase 0 declares no business route, so {@code /api/v1/**} resolves to a 404
 * for an authorized caller — the security chain runs before dispatch, which is what makes the
 * 401/403/404 distinction testable without inventing a fake payment endpoint.
 *
 * <p>Servlet stacks only. A filter chain has nothing to apply to without one, and the
 * {@code JwtDecoder} it needs comes from an auto-configuration that is itself servlet-conditional,
 * so in a non-web context ({@code WebEnvironment.NONE}) this class would ask for a bean that was
 * never created and fail the whole context.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private static final String SCOPE_PAYMENT_READ = "SCOPE_payment:read";
    private static final String SCOPE_PAYMENT_WRITE = "SCOPE_payment:write";
    private static final String SCOPE_OPERATIONS_WRITE = "SCOPE_operations:write";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // The local profile serves these resources. Business endpoints below still
                        // require a JWT, so Swagger's Authorize button is not a security bypass.
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/operations/**")
                        .hasAuthority(SCOPE_OPERATIONS_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/operations/**")
                        .hasAuthority(SCOPE_OPERATIONS_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/**")
                        .hasAuthority(SCOPE_PAYMENT_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/**")
                        .hasAuthority(SCOPE_PAYMENT_WRITE)
                        .anyRequest().denyAll())
                // Set in both places deliberately. exceptionHandling covers a request with no
                // credentials; oauth2ResourceServer covers a token that was supplied and rejected,
                // which the bearer-token filter handles before exceptionHandling is consulted.
                // Wiring only one leaves the other returning Spring's default empty body.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(problemEntryPoint(objectMapper))
                        .accessDeniedHandler(problemAccessDeniedHandler(objectMapper))
                        .jwt(Customizer.withDefaults()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemEntryPoint(objectMapper))
                        .accessDeniedHandler(problemAccessDeniedHandler(objectMapper)))
                .build();
    }

    private AuthenticationEntryPoint problemEntryPoint(ObjectMapper objectMapper) {
        return (request, response, ex) ->
                writeProblem(
                        objectMapper,
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        PayFlowErrorCode.AUTH_UNAUTHENTICATED,
                        "Valid authentication credentials are required.");
    }

    private AccessDeniedHandler problemAccessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, ex) ->
                writeProblem(
                        objectMapper,
                        request,
                        response,
                        HttpStatus.FORBIDDEN,
                        PayFlowErrorCode.AUTH_FORBIDDEN,
                        "Access to this resource is not permitted.");
    }

    private static void writeProblem(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            PayFlowErrorCode code,
            String detail)
            throws IOException {

        ProblemDetail problem =
                ProblemDetails.of(status, code, detail, CorrelationIdFilter.current(request));

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
