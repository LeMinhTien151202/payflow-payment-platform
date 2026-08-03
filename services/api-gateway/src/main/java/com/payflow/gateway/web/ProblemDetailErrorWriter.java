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
 * Ghi kết quả lỗi RFC 9457 Problem Details cho các thất bại ở tầng Edge mà không bao giờ tới controller.
 *
 * <p>Các entry point bảo mật reactive mặc định của Spring trả về body rỗng kèm theo header
 * {@code WWW-Authenticate}. AGENTS.md phần 7 yêu cầu mọi lỗi đều phải là Problem Details
 * kèm theo mã code định danh ổn định, do đó response 401 và 403 được render tại đây.
 */
@Component
public class ProblemDetailErrorWriter {

    private final ObjectMapper objectMapper;

    public ProblemDetailErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Render body Problem Details cho exchange hiện tại.
     *
     * <p>{@code detail} phải giữ dạng tổng quát. Việc giải thích scope nào bị thiếu sẽ vô tình làm cho caller
     * chưa xác thực biết về mô hình phân quyền, do đó thông điệp chỉ nêu rằng truy cập bị từ chối.
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
            // Việc serialise một ProblemDetail về mặt thực tế không thể thất bại; nếu xảy ra lỗi, status code
            // vẫn đúng và một body rỗng vẫn tốt hơn là rò rỉ ngoại lệ (exception) cho client.
            return exchange.getResponse().setComplete();
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Body cho request thiếu, hỏng định dạng, hoặc hết hạn credential. */
    public Mono<Void> unauthenticated(ServerWebExchange exchange) {
        return write(
                exchange,
                HttpStatus.UNAUTHORIZED,
                PayFlowErrorCode.AUTH_UNAUTHENTICATED,
                "Valid authentication credentials are required.");
    }

    /** Body cho caller đã được xác thực nhưng thiếu authority bắt buộc. */
    public Mono<Void> forbidden(ServerWebExchange exchange) {
        return write(
                exchange,
                HttpStatus.FORBIDDEN,
                PayFlowErrorCode.AUTH_FORBIDDEN,
                "Access to this resource is not permitted.");
    }
}
