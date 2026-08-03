package com.payflow.events;

import com.payflow.observability.CorrelationId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope duy nhất cho mọi thông điệp PayFlow Kafka, theo spec 8.2.
 *
 * <p>Tên component là wire format. Việc đổi tên là breaking change đối với mọi consumer, nên
 * không được refactor chỉ vì phong cách trình bày. Cố tình không dùng annotation serialisation: hình
 * dạng JSON là một contract do Jackson tạo ra, không phải một Jackson mapping tùy ý.
 *
 * <p><strong>{@code eventId} được tạo khi dòng outbox được insert, không phải khi thông điệp được
 * publish.</strong> ADR-014 phụ thuộc vào điều này: một đợt republish sau khi crash phải mang cùng
 * {@code eventId} với lượt gửi có thể đã tới broker, nếu không consumer inbox không thể nhận biết
 * duplicate và toàn bộ chuỗi at-least-once sẽ mất hiệu lực.
 *
 * @param eventId định danh định hình của event này, và là key deduplication cho mọi consumer
 * @param eventType tên contract, ví dụ {@code payment.created}
 * @param eventVersion schema version của {@code data}
 * @param aggregateType loại aggregate, ví dụ {@code PAYMENT}
 * @param aggregateId instance của aggregate; đồng thời là Kafka key giữ thứ tự per-aggregate
 *     bên trong một partition (spec 8.3)
 * @param correlationId liên kết event này ngược về HTTP request gây ra nó
 * @param causationId {@code eventId} của event đã gây ra event này, {@code null} khi nguyên nhân
 *     là một inbound request thay vì một event khác
 * @param producer service đã ghi dòng outbox
 * @param occurredAt thời điểm xảy ra sự thật nghiệp vụ, không phải thời điểm publish
 * @param data payload có version; schema của nó do service phát hành sở hữu
 * @param <T> kiểu payload
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        String aggregateId,
        String correlationId,
        String causationId,
        String producer,
        Instant occurredAt,
        T data) {

    /** Khớp với {@code outbox_events.aggregate_id}, chứa chuỗi UUID trong mọi trường hợp sử dụng hiện tại. */
    public static final int MAX_AGGREGATE_ID_LENGTH = 100;

    /** Khớp với độ rộng cột producer được sử dụng bởi các bảng outbox và inbox. */
    public static final int MAX_PRODUCER_LENGTH = 100;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId is required");
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be at least 1, was " + eventVersion);
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (aggregateId.length() > MAX_AGGREGATE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "aggregateId exceeds " + MAX_AGGREGATE_ID_LENGTH + " characters");
        }
        if (producer == null || producer.isBlank()) {
            throw new IllegalArgumentException("producer is required");
        }
        if (producer.length() > MAX_PRODUCER_LENGTH) {
            throw new IllegalArgumentException(
                    "producer exceeds " + MAX_PRODUCER_LENGTH + " characters");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(data, "data is required");

        // Correlation id chuyển đến đây từ một HTTP header do client kiểm soát, và từ đây
        // nó sẽ đi vào một dòng lưu trữ, một dòng log, và một Kafka header. Việc reject thay vì
        // sanitise là cố ý: tại thời điểm này request filter đã thay thế bất kỳ giá trị
        // không an toàn nào, nên một giá trị không an toàn có nghĩa là một code path đã bỏ qua filter.
        if (!CorrelationId.isSafe(correlationId)) {
            throw new IllegalArgumentException("correlationId is missing or not safe to propagate");
        }
        if (causationId != null && !CorrelationId.isSafe(causationId)) {
            throw new IllegalArgumentException("causationId is not safe to propagate");
        }
    }

    /**
     * Tạo envelope cho một sự thật nghiệp vụ được gây ra bởi một inbound request thay vì một event khác.
     *
     * @param eventId định danh được gán; caller cung cấp vì nó phải được sinh ra một lần,
     *     tại thời điểm insert outbox, và dùng lại bởi mọi đợt republish
     */
    public static <T> EventEnvelope<T> of(
            UUID eventId,
            EventType type,
            String aggregateId,
            String correlationId,
            String producer,
            Instant occurredAt,
            T data) {

        return new EventEnvelope<>(
                eventId,
                type.name(),
                type.version(),
                type.aggregateType(),
                aggregateId,
                correlationId,
                null,
                producer,
                occurredAt,
                data);
    }

    /**
     * Tạo envelope cho một sự thật nghiệp vụ được gây ra bởi việc consume một event khác, ghi nhận nguyên nhân đó
     * vào {@code causationId}. Nếu không có liên kết này, một Saga chỉ có thể truy vết được qua correlation id,
     * vốn chỉ cho biết request chứ không cho biết chuỗi các bước bên trong nó.
     */
    public static <T> EventEnvelope<T> causedBy(
            UUID eventId,
            EventType type,
            String aggregateId,
            EventEnvelope<?> cause,
            String producer,
            Instant occurredAt,
            T data) {

        return new EventEnvelope<>(
                eventId,
                type.name(),
                type.version(),
                type.aggregateType(),
                aggregateId,
                cause.correlationId(),
                cause.eventId().toString(),
                producer,
                occurredAt,
                data);
    }
}
