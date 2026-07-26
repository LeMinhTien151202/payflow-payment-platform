package com.payflow.payment.infrastructure.web;

import com.payflow.observability.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the correlation id to the request thread so every log line during the request carries it.
 *
 * <p>The gateway normally supplies the id. This service still accepts a direct call, for example
 * from another service, so it falls back to generating one rather than logging without an id.
 *
 * <p>Runs before the security filter chain so authentication failures are also correlated. MDC is
 * cleared in a {@code finally} block: servlet containers pool threads, and a leaked MDC entry would
 * stamp an unrelated later request with this request's id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Request attribute holding the resolved correlation id. */
    public static final String ATTRIBUTE = "payflow.correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId =
                CorrelationId.resolveOrGenerate(request.getHeader(CorrelationId.HEADER));

        request.setAttribute(ATTRIBUTE, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        MDC.put(CorrelationId.MDC_KEY, correlationId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /** Reads the correlation id established for the current request, or {@code null} if absent. */
    public static String current(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
