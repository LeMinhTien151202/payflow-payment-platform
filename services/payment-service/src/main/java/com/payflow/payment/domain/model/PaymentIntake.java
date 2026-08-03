package com.payflow.payment.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tất cả thông tin cần thiết để tạo một payment, đã được validate và typed.
 *
 * <p>Tồn tại để {@link Payment#create} chỉ nhận hai tham số thay vì mười. Một factory với mười
 * tham số theo vị trí, trong đó bốn tham số là kiểu string, rất dễ dẫn đến lỗi nhầm vị trí tham số — và việc tráo đổi
 * {@code merchantReference} với {@code idempotencyKey} vẫn sẽ biên dịch, pass test, nhưng âm thầm phá hỏng
 * cơ chế idempotency.
 *
 * <p>Được định nghĩa trong domain layer thay vì dùng lại command object của application layer, để hướng phụ thuộc
 * vẫn trỏ vào bên trong. Việc chuyển đổi giữa hai object được viết tay trong application layer,
 * giúp người review code có thể nhìn thấy rõ ràng từng bước.
 *
 * <p>Identifier và timestamp được cung cấp bởi caller, không phải sinh ra ở đây. Một domain object
 * tự gọi {@code UUID.randomUUID()} hoặc {@code Instant.now()} sẽ không thể test độc lập về cả 2 giá trị này.
 *
 * @param metadata cặp key/value do merchant cung cấp, có giới hạn kích thước và được copy bảo vệ (defensive copy)
 * @param description văn bản tự do từ merchant; không bao giờ dùng cho việc ra quyết định, chỉ lưu trữ và hiển thị
 */
public record PaymentIntake(
        UUID paymentId,
        UUID customerId,
        UUID sourceAccountId,
        String merchantReference,
        String idempotencyKey,
        Money amount,
        String description,
        Map<String, String> metadata,
        Instant createdAt) {

    /** Khớp với {@code payments.merchant_reference}. */
    public static final int MAX_MERCHANT_REFERENCE_LENGTH = 100;

    /** Khớp với {@code payments.idempotency_key}. */
    public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    /** Khớp với {@code payments.description}. */
    public static final int MAX_DESCRIPTION_LENGTH = 500;

    /**
     * Giới hạn Metadata.
     *
     * <p>Cột DB có kiểu {@code jsonb} và sẵn sàng chấp nhận dung lượng hàng megabyte. Các giới hạn này tồn tại bởi vì
     * giá trị ở đây là nội dung bên thứ ba không xác định nằm trên luồng ghi của payment: nếu không giới hạn, nó sẽ
     * trở thành cách khiến cho mọi câu lệnh insert bị chậm, và là nơi chứa dữ liệu mà nền tảng này chưa bao giờ cam kết
     * bảo vệ.
     */
    public static final int MAX_METADATA_ENTRIES = 20;

    public static final int MAX_METADATA_KEY_LENGTH = 64;

    public static final int MAX_METADATA_VALUE_LENGTH = 512;

    public PaymentIntake {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(createdAt, "createdAt");

        merchantReference =
                requireBounded(merchantReference, "merchantReference", MAX_MERCHANT_REFERENCE_LENGTH);
        idempotencyKey =
                requireBounded(idempotencyKey, "idempotencyKey", MAX_IDEMPOTENCY_KEY_LENGTH);

        // Một payment bằng 0 không phải là một payment (validation theo spec 7.4). API sẽ từ chối việc này đầu tiên
        // bằng một lỗi ở cấp field; việc chạy tới đây với số tiền bằng 0 có nghĩa là luồng validation đã bị bỏ qua, nên đây
        // là một IllegalArgumentException chứ không phải domain rejection.
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("payment amount must be positive: " + amount);
        }
        if (description != null && description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "description must be at most " + MAX_DESCRIPTION_LENGTH + " characters");
        }

        metadata = validatedMetadata(metadata);
    }

    private static String requireBounded(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static Map<String, String> validatedMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        if (metadata.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException(
                    "metadata must have at most " + MAX_METADATA_ENTRIES + " entries");
        }
        metadata.forEach(
                (key, value) -> {
                    requireBounded(key, "metadata key", MAX_METADATA_KEY_LENGTH);
                    if (value == null || value.length() > MAX_METADATA_VALUE_LENGTH) {
                        throw new IllegalArgumentException(
                                "metadata value for '"
                                        + key
                                        + "' must be non-null and at most "
                                        + MAX_METADATA_VALUE_LENGTH
                                        + " characters");
                    }
                });
        // Dùng Map.copyOf, để caller giữ map ban đầu không thể thay đổi những gì đã được validate.
        return Map.copyOf(metadata);
    }
}
