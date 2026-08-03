package com.payflow.payment.application.command;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Một yêu cầu tạo payment, theo góc nhìn của application layer.
 *
 * <p>{@code merchantId} đứng đầu tiên, và không nằm trong HTTP request body. Body trong spec 7.4 không có
 * field merchant: merchant chính là caller đã được xác thực, giải mã từ token. AGENTS.md phần 8 chỉ rõ
 * rằng ownership không bao giờ được lấy từ request body — một merchant nếu tự đặt tên cho mình thì cũng có thể đặt tên merchant khác.
 *
 * <p>{@code idempotencyKey} đến từ header {@code Idempotency-Key} chứ không phải body, nên nó được
 * mang riêng biệt với các field mô tả payment. Sự tách biệt đó giúp request fingerprint chỉ bao phủ
 * nội dung payment chứ không chứa key.
 *
 * <p>Amount và currency giữ nguyên dạng các kiểu dữ liệu nguyên thủy riêng biệt ở đây và chỉ trở thành {@code Money} bên trong
 * handler. Biên giới API phải nhận bất kỳ dữ liệu nào client gửi lên để từ chối với lỗi 400; việc biến nó
 * thành domain type ngay ở tầng edge sẽ khiến cho một số tiền không hợp lệ trở thành một domain exception thay vì validation failure.
 */
public record CreatePaymentCommand(
        UUID merchantId,
        String idempotencyKey,
        String merchantReference,
        UUID customerId,
        UUID sourceAccountId,
        BigDecimal amount,
        String currency,
        String description,
        Map<String, String> metadata) {

    public CreatePaymentCommand {
        // Chỉ có hai giá trị mà ranh giới API không thể tự mình validate mới được kiểm tra ở đây. Phần còn lại được
        // giới hạn bởi PaymentIntake và Money, và việc nhân bản các quy tắc đó ở một nơi thứ ba là nguyên nhân
        // khiến ba bản sao kết thúc mâu thuẫn với nhau.
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");

        if (metadata == null) {
            metadata = Map.of();
        } else {
            // Kiểm tra trước khi copy thay vì để cho PaymentIntake làm, vì Map.copyOf sẽ phản hồi
            // một giá trị null bằng NullPointerException thuần túy — thứ mà error handler chỉ có thể chuyển thành
            // lỗi 500, cho một sai sót từ phía client.
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException(
                            "metadata value must not be null: " + entry.getKey());
                }
            }
            metadata = Map.copyOf(metadata);
        }
    }
}
