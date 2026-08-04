package com.payflow.payment.application.idempotency;

import com.payflow.payment.application.PaymentAcceptance;
import java.util.Objects;
import java.util.UUID;

/**
 * Một response được lưu trữ, sẵn sàng để được trả về lại.
 *
 * <p>Lưu giữ body đã hoàn tất chứ không phải các đầu vào cần thiết để dựng lại nó. Đó chính là toàn bộ mục đích của
 * bảng này: một payment mà kể từ đó đã chuyển sang {@code SUCCEEDED} sẽ dựng lại dưới dạng {@code SUCCEEDED}, nên một đợt
 * replay dựng từ dòng payment hiện tại sẽ mâu thuẫn với response mà client nhận được ban đầu
 * — và một client retry lại một request tuyệt đối không được thông báo rằng trạng thái đã thay đổi chỉ vì họ vừa retry.
 *
 * <p>{@code body} mang kiểu dữ liệu {@link PaymentAcceptance} bởi vì đây là response được lưu trữ của endpoint tạo payment.
 * Refund intake sử dụng một view riêng theo từng endpoint trên cùng một bảng, ngăn chặn
 * việc cast JSON vô tình giữa hai hợp đồng response có version độc lập.
 *
 * @param requestHash fingerprint của request đã tạo ra response này, dùng để phân biệt đợt replay với đợt dùng lại key
 * @param resourceId payment mà response này mô tả; cũng nằm bên trong {@code body}, và giữ riêng
 *     vì cột DB là thứ giúp câu hỏi "key này đã tạo ra payment nào" thành một query index thay vì scan JSON
 * @param responseStatus mã trạng thái HTTP mà request ban đầu đã trả về
 */
public record IdempotentResponse(
        String requestHash, UUID resourceId, int responseStatus, PaymentAcceptance body) {

    public IdempotentResponse {
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(body, "body");

        if (responseStatus < 100 || responseStatus > 599) {
            throw new IllegalArgumentException("responseStatus is not an HTTP status: " + responseStatus);
        }
    }

    /** Whether {@code candidate} is the same request that produced this response. */
    public boolean matches(String candidate) {
        return requestHash.equals(candidate);
    }
}
