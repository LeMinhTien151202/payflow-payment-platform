package com.payflow.payment.domain.model;

import com.payflow.payment.domain.exception.UnsupportedCurrencyException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * Một số tiền và loại currency mà nó đại diện, không thể tách rời.
 *
 * <p>Việc ghép cặp này chính là điểm mấu chốt. Một {@code BigDecimal} đơn thuần khi truyền qua các method sẽ mất đi thông tin currency,
 * và bug phát sinh từ đó không phải là lỗi crash — nó là một con số trông có vẻ hợp lý nhưng lại hoàn toàn sai.
 *
 * <p>{@code BigDecimal} tại mốc scale cố định là 4, khớp với {@code NUMERIC(19,4)} trong PostgreSQL.
 * AGENTS.md phần 5 cấm hoàn toàn kiểu {@code double} và {@code float} cho tiền tệ: chúng không thể
 * biểu diễn chính xác 0.1, do đó tổng của chúng sẽ bị lệch, và sai số đó sẽ rơi vào số dư của ai đó.
 *
 * <p>Scale được chuẩn hóa ngay khi khởi tạo để hai số tiền có giá trị bằng nhau cũng sẽ bằng nhau thông qua
 * {@code equals}. Nếu không có điều đó, {@code Money.of("100")} và {@code Money.of("100.0000")} sẽ là
 * các object khác nhau đại diện cho cùng một số tiền, và mọi so sánh trong codebase sẽ phải
 * nhớ việc sử dụng {@code compareTo}.
 *
 * <p>Số 0 được chấp nhận; số âm thì không. Một số tiền bằng 0 là một lượng có thật — chưa hoàn tiền, chưa
 * tính phí. Một số âm sẽ mang ý nghĩa hướng giao dịch, vốn thuộc về sự phân biệt debit/credit của sổ cái
 * chứ không phải tuồn vào dấu âm ở đây. Việc một <em>payment</em> phải là số dương nghiêm ngặt
 * là một quy tắc riêng biệt, được thực thi bởi {@link Payment}.
 */
public record Money(BigDecimal amount, String currency) {

    /** Khớp với {@code NUMERIC(19,4)}. Việc thay đổi nó là một database migration, không phải sửa hằng số. */
    public static final int SCALE = 4;

    /**
     * MVP chỉ hỗ trợ VND (spec 7.4).
     *
     * <p>Thêm một loại currency không đơn thuần là thêm một mục ở đây: nó cần nguồn FX-rate, quy tắc làm tròn
     * cho mỗi currency, và quyết định về ý nghĩa số dư merchant đa tiền tệ. Tập hợp hẹp
     * chính là thứ ép buộc cuộc thảo luận đó thay vì vô tình để currency thứ hai xuất hiện mà không được kiểm soát.
     */
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("VND");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter uppercase ISO-4217 code: " + currency);
        }
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new UnsupportedCurrencyException(currency);
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative: " + amount);
        }
        // Từ chối thay vì tự làm tròn. PostgreSQL sẽ làm tròn các chữ số thừa một cách âm thầm, và một
        // policy làm tròn là một quyết định nghiệp vụ không được đưa ra bởi constructor của một value object.
        // Xem PaymentIntakeSchemaIT.extraScaleIsRoundedNotRejected.
        if (amount.scale() > SCALE) {
            throw new IllegalArgumentException(
                    "amount scale must not exceed " + SCALE + ": " + amount);
        }

        // Chính xác theo cấu trúc: scale vừa được kiểm tra tối đa là SCALE, do đó lệnh này mở rộng và
        // không bao giờ làm tròn.
        amount = amount.setScale(SCALE);
    }

    /**
     * @param amount văn bản dạng số thập phân, ví dụ {@code "500000"} hoặc {@code "1234.5678"}
     */
    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /**
     * @throws IllegalArgumentException nếu các currency khác nhau — sự mâu thuẫn ở đây không phải là một lỗi từ chối nghiệp vụ
     *     mà là hai giá trị đến từ các nguồn mâu thuẫn nhau, và việc trả về bất kỳ boolean nào cũng chỉ là sự suy đoán
     */
    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        if (amount.compareTo(other.amount) < 0) {
            throw new IllegalArgumentException("money subtraction would be negative");
        }
        return new Money(amount.subtract(other.amount), currency);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot compare " + currency + " with " + other.currency);
        }
    }

    /** Định dạng hiển thị chuỗi thường (plain notation), để một số tiền lớn không bao giờ hiển thị dưới dạng {@code 5E+5} trong log hay message. */
    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency;
    }
}
