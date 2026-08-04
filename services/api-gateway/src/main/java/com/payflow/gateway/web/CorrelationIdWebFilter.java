package com.payflow.gateway.web;

import com.payflow.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Thiết lập correlation id tại ranh giới edge.
 *
 * <p>Gateway là nơi khởi tạo correlation id nếu client không tự cung cấp, theo
 * ARCHITECTURE.md phần 12. Id sau đó sẽ được:
 *
 * <ol>
 *   <li>lưu dưới dạng exchange attribute để các error handler có thể đưa vào Problem Details
 *   <li>ghi đè lên forwarded request để các downstream service thừa hưởng cùng id
 *   <li>echo lại trong response để caller có thể trích dẫn khi cần hỗ trợ (support request)
 * </ol>
 *
 * <p>Chạy ở mốc ưu tiên cao nhất (highest precedence) để ngay cả các lỗi xác thực, vốn tạo ra bởi security
 * filter chain, vẫn mang theo id này.
 *
 * <p>Dòng log truy cập được ghi với MDC được set và clear xung quanh câu lệnh log duy nhất. MDC bị
 * ràng buộc theo thread (thread-bound) và đây là một chuỗi reactive, do đó nó chỉ an toàn trong khoảng thời gian diễn ra
 * câu lệnh đó; không có thành phần nào khác trong class này được giả định rằng MDC chứa sẵn dữ liệu.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    /** Attribute của Exchange lưu trữ correlation id đã giải mã cho request hiện tại. */
    public static final String ATTRIBUTE = "payflow.correlationId";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId =
                CorrelationId.resolveOrGenerate(
                        exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));

        exchange.getAttributes().put(ATTRIBUTE, correlationId);
        exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);

        ServerHttpRequest forwarded =
                exchange.getRequest()
                        .mutate()
                        .headers(headers -> headers.set(CorrelationId.HEADER, correlationId))
                        .build();

        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();

        return chain.filter(exchange.mutate().request(forwarded).build())
                .doFinally(signal -> logCompletion(exchange, correlationId, method, path));
    }

    private void logCompletion(
            ServerWebExchange exchange, String correlationId, String method, String path) {

        MDC.put(CorrelationId.MDC_KEY, correlationId);
        try {
            log.info(
                    "edge request completed method={} path={} status={}",
                    method,
                    path,
                    exchange.getResponse().getStatusCode());
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
