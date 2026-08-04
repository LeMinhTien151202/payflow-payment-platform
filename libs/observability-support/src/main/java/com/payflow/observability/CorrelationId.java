package com.payflow.observability;

import java.util.UUID;

/**
 * Quy ước về Correlation identity được dùng chung bởi mọi service của PayFlow.
 *
 * <p>Gateway khởi tạo hoặc chuyển tiếp correlation id và mọi service phía sau giữ nguyên nó, nên
 * tên header và tên MDC key phải giống hệt nhau ở mọi nơi. Đó là lý do duy nhất class này nằm trong một
 * shared library thay vì ở từng service.
 *
 * <p>Các giá trị đầu vào là không tin cậy: client điều khiển header. {@link #resolveOrGenerate(String)}
 * do đó từ chối bất kỳ thứ gì có thể làm giả các dòng log hoặc làm đầy bộ lưu trữ log thay vì truyền thẳng nó qua.
 */
public final class CorrelationId {

    /** Request/response header mang correlation id qua các ranh giới HTTP. */
    public static final String HEADER = "X-Correlation-Id";

    /** Key cho SLF4J MDC, để structured logs hiển thị id dưới một tên field ổn định. */
    public static final String MDC_KEY = "correlationId";

    /**
     * Độ dài tối đa chấp nhận cho giá trị đầu vào. Một correlation id là một định danh, không phải payload; việc giới hạn độ dài
     * giữ cho dung lượng log nằm trong mức có thể dự đoán khi client gửi một giá trị bất hợp lý.
     */
    public static final int MAX_LENGTH = 64;

    private CorrelationId() {
    }

    /** Tạo một correlation id mới. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Trả về giá trị đầu vào khi nó an toàn để lan truyền và log, nếu không sẽ tạo mới một id.
     *
     * <p>Một giá trị sai định dạng sẽ được thay thế thay vì bị từ chối bằng một lỗi: việc mất id do client chọn
     * là vô hại, trong khi việc làm thất bại request sẽ biến một vấn đề header hình thức thành một sự cố dừng dịch vụ (outage).
     *
     * @param incoming giá trị header từ request, có thể là {@code null}
     * @return một giá trị an toàn để đặt vào dòng log và chuyển tiếp xuống hạ nguồn
     */
    public static String resolveOrGenerate(String incoming) {
        return isSafe(incoming) ? incoming : generate();
    }

    /**
     * Kiểm tra xem một giá trị có được phép log và chuyển tiếp nguyên bản hay không.
     *
     * <p>Chỉ các ký tự URL không bảo lưu (unreserved) mới được phép. Điều này chặn các ký tự CR/LF, vốn có thể cho phép
     * caller chèn các dòng log giả mạo vào structured logs, và chặn các ký tự điều khiển làm hỏng các bộ log parser.
     */
    public static boolean isSafe(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isAllowed(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '_'
                || c == '.';
    }
}
