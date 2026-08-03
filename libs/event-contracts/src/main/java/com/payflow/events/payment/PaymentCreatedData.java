package com.payflow.events.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Payload của {@code payment.created} v1, theo spec 8.4.
 *
 * <p>{@code amount} là một {@link BigDecimal} và không bao giờ là {@code double}. ADR-007 cấm kiểu
 * số thực dấu phẩy động (binary floating point) cho tiền tệ ở mọi nơi, bao gồm cả event payload: một event
 * được lưu trữ trong outbox và được replay sau đó, nên một sai số làm tròn phát sinh ở đây sẽ tồn tại lâu hơn
 * request đã gây ra nó.
 *
 * <p>Amount và currency giữ nguyên là hai trường phẳng (flat fields) thay vì dùng chung type {@code Money}.
 * {@code MODULE_MAP.md} cấm dùng chung policy tiền tệ trong một shared library, và class {@code Money} là nơi
 * tích tụ các quy tắc làm tròn và scale. Mỗi service tự giữ value object tiền tệ riêng; chỉ có hình dạng trên
 * wire format là được dùng chung.
 *
 * <p>{@code customerId} và {@code sourceAccountId} được copy từ request mà không được kiểm tra với nguồn nào.
 * account-service chưa tồn tại ở thời điểm này, nên Phase 1A không thể xác nhận tài khoản là có thật hay thuộc về
 * customer đó. Việc validation đó sẽ đến cùng với Saga ở Phase 1B — các consumer của v1 không được đọc các trường
 * này như là dữ liệu đã được xác minh.
 *
 * @param paymentId id của aggregate, và là Kafka key cho event này
 * @param merchantId merchant mà payment thuộc về
 * @param customerId customer do merchant cung cấp, chưa được xác minh ở v1
 * @param sourceAccountId tài khoản trích tiền, chưa được xác minh ở v1
 * @param amount số tiền dương, được chuẩn hóa về scale 4
 * @param currency mã chữ ISO-4217; MVP chỉ chấp nhận VND
 * @param createdAt thời điểm payment được chấp nhận
 */
public record PaymentCreatedData(
        UUID paymentId,
        UUID merchantId,
        UUID customerId,
        UUID sourceAccountId,
        BigDecimal amount,
        String currency,
        Instant createdAt) {

    /** ADR-007 cố định số tiền ở dạng {@code NUMERIC(19,4)}, nên payload scale phải khớp với cột trong DB. */
    public static final int MONEY_SCALE = 4;

    public PaymentCreatedData {
        Objects.requireNonNull(paymentId, "paymentId is required");
        Objects.requireNonNull(merchantId, "merchantId is required");
        Objects.requireNonNull(customerId, "customerId is required");
        Objects.requireNonNull(sourceAccountId, "sourceAccountId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(createdAt, "createdAt is required");

        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive, was " + amount);
        }
        if (amount.scale() > MONEY_SCALE) {
            throw new IllegalArgumentException(
                    "amount scale " + amount.scale() + " exceeds " + MONEY_SCALE);
        }
        // Mở rộng chính xác về scale 4 — chính xác, không bao giờ làm tròn, vì scale lớn hơn đã bị
        // từ chối ở trên. Điều này giúp chuỗi JSON sau khi serialise có tính định hình (deterministic),
        // cho phép việc republish sau khi crash tạo ra các byte hoàn toàn giống nhau cho cùng một eventId (ADR-014).
        amount = amount.setScale(MONEY_SCALE);

        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO-4217 code");
        }
        for (int i = 0; i < currency.length(); i++) {
            char c = currency.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new IllegalArgumentException("currency must be uppercase ISO-4217: " + currency);
            }
        }
    }
}
