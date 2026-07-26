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
 * Establishes the correlation id at the edge.
 *
 * <p>The gateway is where a correlation id is created if the client did not supply one, per
 * ARCHITECTURE.md section 12. The id is then:
 *
 * <ol>
 *   <li>stored as an exchange attribute so error handlers can include it in Problem Details
 *   <li>rewritten onto the forwarded request so downstream services inherit the same id
 *   <li>echoed on the response so a caller can quote it in a support request
 * </ol>
 *
 * <p>Runs at highest precedence so that authentication failures, which are produced by the security
 * filter chain, still carry the id.
 *
 * <p>The access log line is written with MDC set and cleared around the single logging call. MDC is
 * thread-bound and this is a reactive chain, so it is only safe for the duration of that one
 * statement; nothing else in this class may assume MDC is populated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    /** Exchange attribute holding the resolved correlation id for the current request. */
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
