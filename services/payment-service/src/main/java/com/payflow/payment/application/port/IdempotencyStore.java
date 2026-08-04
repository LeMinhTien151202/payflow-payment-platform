package com.payflow.payment.application.port;

import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.idempotency.IdempotentResponse;
import java.time.Instant;
import java.util.Optional;

/**
 * Lưu trữ và replay response của một request idempotent.
 *
 * <p>{@link #record} được gọi bên trong transaction riêng của payment, đó là thứ giúp bản ghi và
 * payment xuất hiện cùng nhau hoặc không xuất hiện gì cả. Nó cố ý không cung cấp bước "giữ key trước, điền
 * response sau": dạng 2-phase đó cần một trạng thái {@code IN_PROGRESS}, và một dòng
 * {@code IN_PROGRESS} bị bỏ lại bởi một request bị crash sẽ là một key không bao giờ có thể dùng lại được nữa.
 */
public interface IdempotencyStore {

    /**
     * Response được lưu trữ cho key này, hoặc empty nếu key chưa được sử dụng.
     *
     * <p>Được gọi trước khi mở transaction, nên trường hợp phổ biến — một client không bao giờ retry — chỉ tốn một
     * thao tác đọc indexed và không chiếm lock.
     */
    Optional<IdempotentResponse> find(String scope, String idempotencyKey);

    /**
     * Lưu trữ response để việc lặp lại cùng request có thể được trả lời mà không phải thực hiện lại công việc.
     *
     * @param expiresAt mốc thời gian lưu giữ; dòng dữ liệu ngừng việc có thể replay sau mốc này, và chưa có gì xóa nó
     *     (xem comment cột trong file {@code V2__payment_intake.sql})
     * @throws ConcurrentIdempotentRequestException nếu một request đồng thời đã lưu trữ key này trước
     */
    void record(String scope, String idempotencyKey, IdempotentResponse response, Instant expiresAt);
}
