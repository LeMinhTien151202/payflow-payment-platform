package com.payflow.payment.application.idempotency;

import com.payflow.payment.application.command.CancelPaymentCommand;
import com.payflow.payment.application.command.CreatePaymentCommand;
import com.payflow.payment.application.command.CreateRefundCommand;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Chuyển các write request payment/refund thành các fingerprint lưu trữ trong
 * {@code idempotency_records.request_hash}.
 *
 * <p>Fingerprint trả lời 1 câu hỏi: đây có phải là request giống hệt với request đã sử dụng key này trước đó hay không?
 * Việc so sánh trực tiếp raw request body sẽ cho câu trả lời sai, bởi vì một client sắp xếp lại thứ tự các member JSON hoặc
 * viết {@code 500000.00} thay vì {@code 500000} thực chất đã gửi cùng một payment. Tất cả ở đây tồn tại để
 * làm cho các request tương đương tạo ra các chuỗi bằng nhau, và các request khác nhau tạo ra các chuỗi khác nhau.
 *
 * <p>Ba thuộc tính quan trọng, và mỗi thuộc tính là một quyết định kiến trúc chứ không phải chi tiết nhỏ:
 *
 * <ul>
 *   <li><strong>Length-prefixed.</strong> Mỗi giá trị được encode dạng {@code name:length:value|}. Việc nối chuỗi
 *       đơn thuần sẽ khiến reference {@code "AB"} với description {@code "C"} có cùng byte với
 *       reference {@code "A"} với description {@code "BC"}, trong khi đó là hai payment hoàn toàn khác nhau.
 *   <li><strong>Metadata sorted by key.</strong> Thứ tự member JSON không quan trọng, do đó hai cách sắp xếp
 *       của cùng một metadata không được coi là hai request khác nhau.
 *   <li><strong>Amount stripped of trailing zeros.</strong> {@code 500000}, {@code 500000.00} và
 *       {@code 5E+5} là cùng một số tiền và phải tạo ra fingerprint giống hệt nhau.
 * </ul>
 *
 * <p>Bản thân idempotency key bị loại trừ — nó là key tìm kiếm, chứ không phải một phần của dữ liệu được so sánh.
 * Correlation id cũng bị loại trừ: nó khác nhau ở mỗi lần retry, điều đó sẽ khiến cho mọi đợt retry trở thành lỗi conflict.
 *
 * <p>SHA-256 được sử dụng để có độ rộng cố định ổn định, chứ không phải cho mục đích bảo mật. Không có gì ở đây là credential và hash
 * không bao giờ trả về cho caller, do đó việc so sánh trong {@link IdempotentResponse#matches(String)} không
 * cần phải là constant-time. 64 ký tự hex vừa vặn trong {@code VARCHAR(128)} còn dư chỗ cho một algorithm dài hơn.
 */
public final class RequestFingerprint {

    private static final String ALGORITHM = "SHA-256";

    private RequestFingerprint() {
    }

    /** Chuỗi hex chữ thường SHA-256 của định dạng chuẩn hóa (canonical encoding) từ {@code command}. */
    public static String of(CreatePaymentCommand command) {
        StringBuilder canonical = new StringBuilder();

        field(canonical, "merchantId", text(command.merchantId()));
        field(canonical, "merchantReference", command.merchantReference());
        field(canonical, "customerId", text(command.customerId()));
        field(canonical, "sourceAccountId", text(command.sourceAccountId()));
        field(canonical, "amount", amount(command.amount()));
        field(canonical, "currency", command.currency());
        field(canonical, "description", command.description());

        // TreeMap thay vì sorted stream: các key là string và thứ tự tự nhiên (natural ordering) là
        // thuộc tính duy nhất cần giữ nguyên xuyên suốt các phiên bản JVM.
        for (Map.Entry<String, String> entry : new TreeMap<>(command.metadata()).entrySet()) {
            field(canonical, "metadata." + entry.getKey(), entry.getValue());
        }

        return hex(canonical.toString());
    }

    /** Canonical refund payload. Actor bị loại trừ để đợt retry hợp lệ có thể được replay bởi merchant. */
    public static String of(CreateRefundCommand command) {
        StringBuilder canonical = new StringBuilder();
        field(canonical, "merchantId", text(command.merchantId()));
        field(canonical, "paymentId", text(command.paymentId()));
        field(canonical, "amount", amount(command.amount()));
        field(canonical, "reason", command.reason());
        return hex(canonical.toString());
    }

    /** Canonical cancel payload. Actor is deliberately excluded from retry identity. */
    public static String of(CancelPaymentCommand command) {
        StringBuilder canonical = new StringBuilder();
        field(canonical, "merchantId", text(command.merchantId()));
        field(canonical, "paymentId", text(command.paymentId()));
        return hex(canonical.toString());
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    /**
     * Chuẩn hóa mà không validate. Một số tiền với độ chính xác cao hơn cột cho phép sẽ bị từ chối bởi
     * {@code Money}, kèm theo thông điệp về scale; việc ném ngoại lệ ở đây thay vào đó sẽ báo cáo lỗi
     * fingerprint cho một lỗi vốn là validation error thông thường.
     */
    private static String amount(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static void field(StringBuilder target, String name, String value) {
        target.append(name).append(':');
        if (value == null) {
            // Một đánh dấu phân biệt chứ không phải chuỗi rỗng, để một description bị vắng mặt và một description rỗng
            // không bị coi là cùng một request.
            target.append("null");
        } else {
            target.append(value.length()).append(':').append(value);
        }
        target.append('|');
    }

    private static String hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Mọi JVM đều bắt buộc phải cung cấp SHA-256. Bọc ngoại lệ thay vì khai báo throws, vì không có caller nào
            // có phản hồi có ý nghĩa cho một tiêu chuẩn digest bị thiếu.
            throw new IllegalStateException(ALGORITHM + " is not available", impossible);
        }
    }
}
