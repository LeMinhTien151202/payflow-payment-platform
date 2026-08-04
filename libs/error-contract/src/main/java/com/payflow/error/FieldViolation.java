package com.payflow.error;

import java.util.Objects;

/**
 * Một phần tử trong danh sách {@code fieldErrors} của body Problem Details (spec 10.3).
 *
 * <p>Mang thông tin field và message, và cố ý không lưu giá trị bị từ chối (rejected value). Việc phản hồi lại giá trị sẽ
 * đưa những gì client đã gửi vào response và vào bất kỳ log nào ghi lại response — đối với một
 * API thanh toán, đó là cách mà số thẻ ngân hàng bị rò rỉ ra những nơi không bao giờ được phép chứa nó. Phía client đã biết những gì nó gửi;
 * nó chỉ cần biết field nào bị sai và tại sao.
 *
 * @param field trường request bị sai, theo thuật ngữ của client — {@code amount}, {@code metadata[orderId]}
 * @param message lý do bị từ chối; văn bản an toàn dành cho caller và không chứa chi tiết nội bộ
 */
public record FieldViolation(String field, String message) {

    public FieldViolation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
