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
 * Phân quyền ở tầng Edge (Gateway).
 *
 * <p>Từ chối mặc định (Deny-by-default) theo AGENTS.md phần 8: {@code anyExchange().denyAll()} là quy tắc cuối cùng,
 * nên một route mới sẽ không thể truy cập cho đến khi có ai đó chỉ định authority bắt buộc. Việc quên một quy tắc
 * sẽ tạo ra lỗi 403, chứ không vô tình mở quyền public.
 *
 * <p>Gateway validate token không có nghĩa là downstream service được bỏ qua bước validation. Mỗi service
 * tự validate lại JWT và tự thực thi phân quyền sở hữu (ownership), vì gateway không thể biết merchant nào
 * sở hữu một resource cụ thể.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    /** Scope cần thiết để đọc các tài nguyên payment. */
    private static final String SCOPE_PAYMENT_READ = "SCOPE_payment:read";

    /** Scope cần thiết để tạo hoặc thay đổi các tài nguyên payment. */
    private static final String SCOPE_PAYMENT_WRITE = "SCOPE_payment:write";

    /** Privileged scope isolated from merchant credentials. */
    private static final String SCOPE_OPERATIONS_WRITE = "SCOPE_operations:write";
    private static final String SCOPE_MERCHANT_READ = "SCOPE_merchant:read";
    private static final String SCOPE_MERCHANT_READ_ANY = "SCOPE_merchant:read:any";
    private static final String SCOPE_MERCHANT_WRITE = "SCOPE_merchant:write";
    private static final String SCOPE_MERCHANT_WRITE_ANY = "SCOPE_merchant:write:any";
    private static final String SCOPE_REPORTING_READ = "SCOPE_reporting:read";
    private static final String SCOPE_REPORTING_REBUILD = "SCOPE_reporting:rebuild";
    private static final String SCOPE_WEBHOOK_RETRY = "SCOPE_webhook:retry";

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http, ProblemDetailErrorWriter errors) {

        return http
                // API dùng bearer-token phi trạng thái (stateless): không có browser session hay CSRF token để bảo vệ.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        // Chỉ áp dụng cho Liveness/readiness. Toàn bộ các actuator endpoint còn lại giữ private theo
                        // AGENTS.md phần 10.
                        .pathMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/operations/payments/**")
                        .hasAuthority(SCOPE_OPERATIONS_WRITE)
                        .pathMatchers(HttpMethod.POST, "/api/v1/operations/webhooks/**")
                        .hasAuthority(SCOPE_WEBHOOK_RETRY)
                        .pathMatchers(HttpMethod.POST, "/api/v1/operations/reporting/**")
                        .hasAuthority(SCOPE_REPORTING_REBUILD)
                        .pathMatchers(HttpMethod.GET, "/api/v1/reports/**")
                        .hasAuthority(SCOPE_REPORTING_READ)
                        .pathMatchers(HttpMethod.GET, "/api/v1/merchants/**")
                        .hasAnyAuthority(
                                SCOPE_MERCHANT_READ,
                                SCOPE_MERCHANT_READ_ANY,
                                SCOPE_MERCHANT_WRITE,
                                SCOPE_MERCHANT_WRITE_ANY)
                        .pathMatchers(HttpMethod.POST, "/api/v1/merchants")
                        .hasAuthority(SCOPE_MERCHANT_WRITE_ANY)
                        .pathMatchers("/api/v1/merchants/**")
                        .hasAnyAuthority(SCOPE_MERCHANT_WRITE, SCOPE_MERCHANT_WRITE_ANY)
                        .pathMatchers(HttpMethod.GET, "/api/v1/payments/**")
                        .hasAuthority(SCOPE_PAYMENT_READ)
                        .pathMatchers(HttpMethod.POST, "/api/v1/payments/**")
                        .hasAuthority(SCOPE_PAYMENT_WRITE)
                        .anyExchange().denyAll())
                // Entry point được thiết lập ở cả hai nơi là có mục đích. exceptionHandling xử lý cho
                // request hoàn toàn không mang credential nào, trong khi oauth2ResourceServer xử lý cho
                // token được cung cấp nhưng bị từ chối — lỗi đó do bearer-token filter xử lý
                // và không bao giờ tới exceptionHandling. Việc chỉ cấu hình một nơi sẽ khiến đường dẫn kia
                // trả về empty body mặc định của Spring, dẫn tới error contract bị phụ thuộc vào
                // việc caller có gửi token hay không.
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
