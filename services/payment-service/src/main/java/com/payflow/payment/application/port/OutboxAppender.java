package com.payflow.payment.application.port;

import com.payflow.events.EventType;
import com.payflow.events.EventEnvelope;
import java.time.Instant;
import java.util.UUID;

/**
 * Gửi một event vào outbox, bên trong transaction của caller.
 *
 * <p>Đây là cách duy nhất service này tạo ra event. AGENTS.md phần 6 cấm việc publish tới Kafka từ một
 * luồng business path: một thao tác send xảy ra trong khi transaction vẫn đang mở có thể thành công nhưng sau đó bị rolled
 * back, điều đó sẽ publish một payment vốn không hề tồn tại.
 *
 * <p>Application layer chỉ định tên event type và topic bởi vì cả hai đều là các quyết định hợp đồng (contract decisions) —
 * sự thật nào đang được công bố, và ai dự kiến sẽ lắng nghe nó. Những gì adapter sở hữu là tất cả mọi thứ về
 * row: id, envelope lắp ráp xung quanh {@code data}, các header, trạng thái ban đầu, và thời gian
 * thử lần đầu (first attempt time).
 */
public interface OutboxAppender {

    /**
     * Append một event.
     *
     * @param type contract name, version, và aggregate kind
     * @param topic destination, lấy từ {@code PayFlowTopics}
     * @param aggregateId aggregate mà event này đề cập tới; cũng là Kafka key, thứ giữ cho các event
     *     cho 1 payment luôn đúng thứ tự (spec 8.3)
     * @param occurredAt thời điểm sự thật nghiệp vụ xảy ra, lấy từ {@code Clock} của caller — chứ không phải khi
     *     row được ghi hay khi nó sẽ được publish
     * @param data payload có version, sở hữu bởi service này
     * @return {@code eventId} được gán, cũng chính là row id và key chống trùng (deduplication key) cho consumer
     */
    <T> UUID append(EventType type, String topic, String aggregateId, Instant occurredAt, T data);

    /**
     * Append một event được tạo ra bởi một event đã tiêu thụ (consumed event), bảo toàn thông tin correlation và causation metadata.
     * Caller phải gọi method này bên trong cùng local transaction với inbox và các thao tác ghi nghiệp vụ của nó.
     */
    <T> UUID appendCausedBy(
            EventType type,
            String topic,
            String aggregateId,
            Instant occurredAt,
            T data,
            EventEnvelope<?> cause);
}
