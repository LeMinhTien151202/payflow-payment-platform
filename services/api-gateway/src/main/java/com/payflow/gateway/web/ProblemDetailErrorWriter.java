package com.payflow.gateway.web;

import com.payflow.error.PayFlowErrorCode;
import com.payflow.error.ProblemDetails;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes RFC 9457 Problem Details for edge failures that never reach a controller.
 *
 * <p>Spring's reactive security entry points default to an empty body with a
 * {@code WWW-Authenticate} header. AGENTS.md section 7 requires every error to be Problem Details
 * with a stable code, so 401 and 403 responses are rendered here instead.
 */
@Component
public class ProblemDetailErrorWriter {

    private final ObjectMapper objectMapper;

    public ProblemDetailErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Renders a Problem Details body for the current exchange.
     *
     * <p>{@code detail} must stay generic. Explaining which scope was missing would tell an
     * unauthenticated caller about the authorization model, so the message says only that access was
     * refused.
     */
    public Mono<Void> write(
            ServerWebExchange exchange, HttpStatus status, PayFlowErrorCode code, String detail) {

        String correlationId = (String) exchange.getAttribute(CorrelationIdWebFilter.ATTRIBUTE);
        ProblemDetail problem = ProblemDetails.of(status, code, detail, correlationId);

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(problem);
        } catch (JacksonException e) {
            // Serialising a ProblemDetail cannot realistically fail; if it does, the status code is
            // still correct and an empty body is preferable to leaking an exception to the client.
            return exchange.getResponse().setComplete();
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Body for a request with missing, malformed, or expired credentials. */
    public Mono<Void> unauthenticated(ServerWebExchange exchange) {
        return write(
                exchange,
                HttpStatus.UNAUTHORIZED,
                PayFlowErrorCode.AUTH_UNAUTHENTICATED,
                "Valid authentication credentials are required.");
    }

    /** Body for an authenticated caller lacking the required authority. */
    public Mono<Void> forbidden(ServerWebExchange exchange) {
        return write(
                exchange,
                HttpStatus.FORBIDDEN,
                PayFlowErrorCode.AUTH_FORBIDDEN,
                "Access to this resource is not permitted.");
    }
}
