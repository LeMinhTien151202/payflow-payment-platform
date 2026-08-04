package com.payflow.error;

import com.payflow.observability.CorrelationId;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Dựng cấu trúc mở rộng PayFlow cho RFC 9457 Problem Details.
 *
 * <p>Mỗi response lỗi đều mang 4 trường mở rộng ngoài RFC:
 *
 * <ul>
 *   <li>{@code code} — một mã lỗi nghiệp vụ/platform ổn định mà client có thể rẽ nhánh xử lý
 *   <li>{@code correlationId} — id cần thiết để tìm kiếm request trong log và trace
 *   <li>{@code timestamp} — thời điểm body lỗi được khởi tạo
 *   <li>{@code fieldErrors} — thông tin lỗi validation cho từng trường, rỗng đối với các lỗi không liên quan đến từng trường
 * </ul>
 *
 * <p>{@code fieldErrors} luôn hiện diện, ngay cả khi rỗng. Một client phải phân biệt giữa "absent" và
 * "empty" trước khi đọc danh sách sẽ phải xử lý hai cấu trúc cho cùng một ý nghĩa.
 *
 * <p>{@code detail} là văn bản chỉ dành riêng cho caller. Stack traces, SQL, driver messages, và internal
 * hostnames tuyệt đối không được rò rỉ vào đây.
 */
public final class ProblemDetails {

    /** Trường mở rộng chứa mã lỗi ổn định. */
    public static final String FIELD_CODE = "code";

    /** Trường mở rộng chứa correlation id phục vụ hỗ trợ và tra cứu log. */
    public static final String FIELD_CORRELATION_ID = "correlationId";

    /** Trường mở rộng chứa thời điểm body lỗi được dựng. */
    public static final String FIELD_TIMESTAMP = "timestamp";

    /** Trường mở rộng chứa các lỗi validation theo từng trường. */
    public static final String FIELD_FIELD_ERRORS = "fieldErrors";

    private ProblemDetails() {
    }

    /**
     * Tạo một body Problem Details với các trường mở rộng của PayFlow được thiết lập.
     *
     * @param status Mã trạng thái HTTP cho response
     * @param code Mã lỗi ổn định phía client có thể rẽ nhánh xử lý
     * @param detail Lời giải thích an toàn dành cho caller; không được chứa chi tiết nội bộ
     * @param correlationId correlation id của request hiện tại, có thể là {@code null}
     */
    public static ProblemDetail of(
            HttpStatus status, ErrorCode code, String detail, String correlationId) {

        return enrich(ProblemDetail.forStatusAndDetail(status, detail), code, correlationId);
    }

    /**
     * Thêm các trường mở rộng của PayFlow vào body Problem Details mà Spring đã khởi tạo sẵn,
     * ví dụ từ {@code ResponseEntityExceptionHandler}.
     */
    public static ProblemDetail enrich(
            ProblemDetail problem, ErrorCode code, String correlationId) {

        problem.setProperty(FIELD_CODE, code.code());
        if (CorrelationId.isSafe(correlationId)) {
            problem.setProperty(FIELD_CORRELATION_ID, correlationId);
        }
        problem.setProperty(FIELD_TIMESTAMP, timestamp());
        problem.setProperty(FIELD_FIELD_ERRORS, List.of());
        return problem;
    }

    /**
     * Thay thế thành phần {@code fieldErrors}. Trả về cùng một body, để có thể chain tiếp vào
     * {@link #of(HttpStatus, ErrorCode, String, String)}.
     */
    public static ProblemDetail withFieldErrors(
            ProblemDetail problem, List<FieldViolation> violations) {

        problem.setProperty(FIELD_FIELD_ERRORS, List.copyOf(violations));
        return problem;
    }

    /**
     * Đọc thời gian từ system clock thay vì từ một clock được inject vào.
     *
     * <p>Đây là thông tin chẩn đoán — nó cho operator biết khi nào lỗi được render — và không có invariant, giá trị lưu trữ,
     * hay quyết định nghiệp vụ nào phụ thuộc vào nó. Instance {@code Clock} được inject tồn tại cho các timestamp được lưu vào
     * database hoặc event, nơi một test case cần có khả năng cố định thời gian; việc trỏ nó vào đường đi của lỗi
     * sẽ khiến mọi component có khả năng thất bại phải nhận thêm một constructor dependency chỉ để tạo ra một field
     * không có gì assert đến.
     */
    private static Instant timestamp() {
        return Instant.now();
    }
}
