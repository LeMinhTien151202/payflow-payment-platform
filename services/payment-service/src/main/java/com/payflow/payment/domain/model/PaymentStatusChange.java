package com.payflow.payment.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Một bước chuyển của payment qua state machine, như những gì sẽ được ghi vào
 * {@code payment.payment_status_history}.
 *
 * <p>{@code from} mang giá trị null ở entry đầu tiên, vốn ghi nhận sự ra đời của payment chứ không phải
 * một bước chuyển. Tất cả thông tin còn lại đều bắt buộc, vì một dòng lịch sử không thể chỉ ra thời điểm xảy ra
 * sự kiện sẽ không thể trả lời bất kỳ câu hỏi có ý nghĩa nào.
 *
 * <p>Bảng lịch sử cũng có một cột {@code metadata} mà bản ghi này không có field tương ứng. Không có gì
 * trong Phase 1A có dữ liệu để đưa vào đó, và việc tự bịa ra một cấu trúc lúc này sẽ làm cố định định dạng của một
 * cột audit trước khi có một reader nào cần đến nó.
 *
 * @param occurredAt được cung cấp bởi caller từ một {@code Clock} được inject vào, không bao giờ đọc từ hệ thống
 *     system clock tại đây — một domain object tự mình đọc thời gian sẽ không thể kiểm thử độc lập về mặt thời gian
 */
public record PaymentStatusChange(
        PaymentStatus from, PaymentStatus to, String reasonCode, Instant occurredAt) {

    /** Matches {@code payment_status_history.reason_code}. */
    public static final int MAX_REASON_CODE_LENGTH = 100;

    public PaymentStatusChange {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(occurredAt, "occurredAt");

        if (from == to) {
            throw new IllegalArgumentException("a status change must record movement, got " + to);
        }
        if (reasonCode != null && reasonCode.length() > MAX_REASON_CODE_LENGTH) {
            throw new IllegalArgumentException(
                    "reasonCode must be at most " + MAX_REASON_CODE_LENGTH + " characters");
        }
    }

    /** The entry recording a payment's creation, where there is no previous status. */
    public static PaymentStatusChange initial(PaymentStatus to, Instant occurredAt) {
        return new PaymentStatusChange(null, to, null, occurredAt);
    }

    public boolean isInitial() {
        return from == null;
    }
}
