# Kafka trong PayFlow: khái niệm nền và luồng xử lý thực tế

Tài liệu này gồm hai phần ghép lại:

- **Phần A (mục 1–5)**: Kafka là gì, giải thích từ đầu, không giả định bạn đã biết message queue.
- **Phần B (mục 6–17)**: PayFlow dùng Kafka chính xác như thế nào — topic nào, envelope nào, class nào, config nào, retry ra sao, debug ở đâu.

Nếu bạn muốn luồng nghiệp vụ payment/refund end-to-end thì đọc
[`current-business-code-flow-guide.md`](current-business-code-flow-guide.md).
Nếu muốn bản ngắn về "REST dùng ở đâu, Kafka dùng ở đâu" thì đọc
[`rest-kafka-flow-guide.md`](rest-kafka-flow-guide.md).
Tài liệu này đi sâu vào **riêng phần Kafka**.

---

# PHẦN A — KHÁI NIỆM KAFKA

## 1. Kafka là gì, và không phải là gì

Kafka là một **hệ thống log phân tán, append-only, có lưu trữ**.

Cách hiểu sai phổ biến nhất: "Kafka là message queue như RabbitMQ". Nó gần hơn với **một file log khổng lồ mà nhiều người cùng đọc**.

Khác biệt cốt lõi:

| | Queue truyền thống | Kafka |
| --- | --- | --- |
| Message sau khi đọc | Bị xóa khỏi queue | Vẫn còn trong log |
| Ai biết đã đọc tới đâu | Broker giữ trạng thái | **Consumer tự giữ**, bằng một con số gọi là offset |
| Nhiều nhóm consumer cùng đọc | Phải fanout/copy | Đọc chung một log, mỗi nhóm có offset riêng |
| Đọc lại message cũ | Thường không được | Được, chỉ cần lùi offset |

Điều này rất quan trọng với PayFlow: Notification Service và Risk Service đều đọc topic `payflow.payment.events.v1`, nhưng **không tranh nhau message**. Mỗi service là một consumer group riêng, mỗi group có offset riêng, cùng đọc cùng một log.

## 2. Sáu khái niệm bắt buộc phải hiểu

### 2.1. Broker

Một process Kafka đang chạy. Nó nhận message, ghi xuống disk, phục vụ consumer đọc.

PayFlow local chạy **1 broker duy nhất** (container `payflow-kafka`, image `apache/kafka:4.3.1`). Production thật sẽ có nhiều broker để chịu lỗi.

### 2.2. Topic

Một cái tên để nhóm các message cùng loại. Ví dụ `payflow.payment.events.v1`.

Topic **không phải một file duy nhất**. Nó được chia thành partition.

### 2.3. Partition

Một topic được chia thành N partition. Mỗi partition là một **log có thứ tự tuyệt đối**, được ghi thêm vào cuối.

```text
Topic payflow.payment.events.v1 (3 partitions)

partition 0: [msg][msg][msg][msg]  <- ghi thêm ở đây
partition 1: [msg][msg]            <- ghi thêm ở đây
partition 2: [msg][msg][msg]       <- ghi thêm ở đây
```

Điểm quan trọng nhất, và cũng là điểm nhiều người hiểu sai:

> **Kafka chỉ đảm bảo thứ tự TRONG MỘT partition, không đảm bảo thứ tự giữa các partition.**

Nghĩa là nếu bạn cần "event A phải được xử lý trước event B", bạn phải đảm bảo A và B nằm cùng partition.

### 2.4. Offset

Vị trí của một message trong partition, là một số nguyên tăng dần.

```text
partition 0: [msg]   [msg]   [msg]   [msg]
offset:        0       1       2       3
```

Consumer nói "tôi đã xử lý xong đến offset 2" bằng cách **commit offset**. Nếu consumer chết và khởi động lại, nó đọc tiếp từ offset đã commit + 1.

### 2.5. Key và cách chọn partition

Khi producer gửi một message, nó có thể kèm một **key**. Kafka tính partition theo công thức đại ý:

```text
partition = hash(key) % số_partition
```

Hệ quả: **cùng một key luôn về cùng một partition**, do đó **cùng key luôn giữ đúng thứ tự**.

PayFlow dùng `paymentId` làm key. Vì vậy:

- Mọi event của payment `abc-123` đều vào cùng một partition.
- Thứ tự các event của payment `abc-123` được giữ nguyên.
- Event của payment `xyz-999` có thể ở partition khác, và thứ tự giữa hai payment khác nhau **không được đảm bảo** — nhưng điều đó không quan trọng, vì hai payment độc lập nhau.

Đây là kỹ thuật gọi là **per-aggregate ordering**: bạn không cần thứ tự toàn cục, bạn chỉ cần thứ tự trong phạm vi một thực thể nghiệp vụ.

### 2.6. Consumer group

Một nhóm consumer instance cùng `group.id`. Kafka chia partition cho các instance trong group:

```text
Topic 3 partitions, group "risk-payment-created-v1"

1 instance:  instance A đọc partition 0, 1, 2
2 instances: instance A đọc partition 0, 1  |  instance B đọc partition 2
3 instances: A đọc p0  |  B đọc p1  |  C đọc p2
4 instances: A,B,C mỗi cái 1 partition  |  D ngồi không
```

Ba điều rút ra:

1. **Scale ngang bị chặn bởi số partition.** 3 partition thì tối đa 3 instance làm việc thật.
2. **Một partition chỉ có một consumer trong group xử lý** tại một thời điểm → không có hai instance cùng xử lý một payment.
3. Khi instance vào/ra, Kafka **rebalance** — chia lại partition. Trong lúc rebalance có thể có message được xử lý lại nếu offset chưa commit.

Mỗi group có offset riêng. Đó là lý do Risk và Notification cùng đọc `payflow.payment.events.v1` mà không xung đột.

## 3. Delivery semantics: at-most-once, at-least-once, exactly-once

Đây là phần quyết định cách thiết kế hệ thống tiền.

Giả sử consumer nhận message, xử lý, rồi commit offset.

### At-most-once — commit trước, xử lý sau

```text
nhận message -> commit offset -> xử lý
                                  ^ crash ở đây
```

Message đã commit nhưng chưa xử lý → **mất message**. Với tiền thì không chấp nhận được.

### At-least-once — xử lý trước, commit sau

```text
nhận message -> xử lý -> commit offset
                          ^ crash ở đây
```

Đã xử lý nhưng chưa commit → khi khởi động lại, Kafka giao lại message đó → **xử lý hai lần**.

Đây là mô hình PayFlow chọn. Nhưng "xử lý hai lần" với tiền là thảm họa: trừ tiền hai lần, gửi email hai lần. Nên PayFlow bắt buộc phải có **idempotent consumer** (mục 11).

### Exactly-once — vì sao PayFlow không tuyên bố có

Kafka có transaction và `enable.idempotence` cho phía producer. Nhưng "exactly-once" đó chỉ áp dụng trong phạm vi Kafka-to-Kafka. Ngay khi consumer ghi vào PostgreSQL, bạn có hai hệ thống lưu trữ khác nhau và không có một transaction chung.

Ví dụ cụ thể trong PayFlow, ở [`KafkaOutboxTransport.java`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/KafkaOutboxTransport.java):

```java
} catch (TimeoutException timeout) {
    // Broker vẫn có thể đã nhận và chấp nhận đợt gửi này. Việc retry có thể tạo trùng (duplicate), đó là lý do vì sao eventId
    // ổn định và các consumer cần một inbox; tuyệt đối không diễn giải lỗi timeout thành một từ chối chắc chắn.
    throw timeout;
}
```

Khi producer gửi mà bị timeout, nó **không biết** broker đã nhận hay chưa. Retry có thể tạo bản trùng. Không có cách nào tránh điều này bằng config.

Vì vậy PayFlow tuyên bố đúng như sau:

> **at-least-once delivery + idempotent processing**, không phải end-to-end exactly-once.

Đây là ràng buộc trong `.agent/AGENTS.md` và không được phát biểu sai trong bất kỳ tài liệu nào.

## 4. Retention: message tồn tại bao lâu?

Kafka giữ message theo thời gian hoặc dung lượng, **không phải theo việc đã đọc hay chưa**. Mặc định thường là 7 ngày.

Hệ quả:

- Consumer bị dừng 2 ngày rồi bật lại vẫn đọc được message cũ.
- Consumer bị dừng quá retention thì message **mất vĩnh viễn** đối với nó.

Với PayFlow, đây là một lý do nữa để **không coi Kafka là nguồn sự thật**. Nguồn sự thật là PostgreSQL của mỗi service. Kafka chỉ là đường truyền.

## 5. KRaft: vì sao không thấy ZooKeeper

Kafka phiên bản cũ cần ZooKeeper để quản lý metadata cluster. Từ Kafka 3.3+ có **KRaft mode**, Kafka tự quản metadata bằng Raft, không cần ZooKeeper.

PayFlow dùng Kafka 4.x với KRaft. Nếu bạn đọc tutorial cũ và thắc mắc "tại sao docker-compose không có ZooKeeper" — đó là lý do.

---

# PHẦN B — PAYFLOW DÙNG KAFKA NHƯ THẾ NÀO

## 6. Bản đồ topic

Tên topic bị khóa cứng trong [`PayFlowTopics.java`](../../libs/event-contracts/src/main/java/com/payflow/events/PayFlowTopics.java), không đọc từ config. Lý do được ghi trong javadoc: một tên topic khác nhau giữa các môi trường biến "consumer không nhận được event" thành lỗi không thể chứng minh.

| Topic | Ai produce | Ai consume | Nội dung |
| --- | --- | --- | --- |
| `payflow.payment.events.v1` | payment-service | risk-service, account-ledger-service, notification-service | `payment.created`, các command Payment Saga phát ra (reserve/capture/release/ledger post/refund credit), và payment outcome |
| `payflow.risk.events.v1` | risk-service | payment-service | `risk.assessment.completed` |
| `payflow.account.events.v1` | account-ledger-service | payment-service | kết quả reserve/capture/release/refund-credit |
| `payflow.ledger.events.v1` | account-ledger-service | payment-service | kết quả post journal payment/refund |
| `payflow.refund.events.v1` | payment-service | account-ledger-service, notification-service | `refund.requested`, `refund.succeeded`, `refund.failed` |
| `payflow.dead-letter.v1` | mọi consumer khi hết retry | con người / runbook | message không xử lý được |
| `payflow.notification.commands.v1` | — | — | **đã đặt tên nhưng chưa dùng** trong luồng hiện tại (Phase 2) |
| `payflow.settlement.events.v1` | — | — | **đã đặt tên nhưng chưa có service** (Phase 3) |

Điểm dễ gây bối rối: `payflow.payment.events.v1` chứa **cả event lẫn command**.

Cụ thể, khi Payment Saga muốn Account reserve tiền, nó không phát vào `payflow.account.events.v1`. Nó phát `account.reserve.requested` vào `payflow.payment.events.v1`. Lý do: quy tắc là **service nào sở hữu topic thì service đó produce**. `payflow.account.events.v1` thuộc account-ledger-service, nên chỉ account-ledger-service được ghi vào đó. Payment gửi command trên topic của chính nó.

Nhớ theo hướng này:

```text
payflow.payment.events.v1  = "Payment nói gì"  (gồm cả yêu cầu gửi service khác)
payflow.account.events.v1  = "Account trả lời gì"
payflow.ledger.events.v1   = "Ledger trả lời gì"
payflow.risk.events.v1     = "Risk trả lời gì"
```

## 7. Topic được tạo ra như thế nào

Auto-create bị **tắt cố ý** trong `docker-compose.yml`:

```yaml
# Tắt cố ý. Lỗi chính tả tên topic phải báo lỗi rõ ràng thay vì âm thầm tạo
# một topic rỗng mà không bao giờ nhận được event mà các service đang chờ đợi.
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
```

Nếu để bật, một producer viết sai `payflow.payment.event.v1` (thiếu chữ `s`) sẽ tạo ra topic mới rỗng và consumer ngồi chờ mãi mãi mà không có lỗi nào. Tắt đi thì lỗi nổ ngay.

Topic được tạo bởi **hai lớp**:

**Lớp 1 — container `kafka-init`** chạy một lần khi `docker compose up`, tạo 6 topic:

```bash
for topic in \
  payflow.payment.events.v1 \
  payflow.account.events.v1 \
  payflow.ledger.events.v1 \
  payflow.risk.events.v1 \
  payflow.refund.events.v1 \
  payflow.dead-letter.v1
do
  kafka-topics.sh --bootstrap-server kafka:19092 \
    --create --if-not-exists --topic "$topic" \
    --partitions 3 --replication-factor 1
done
```

**Lớp 2 — `NewTopic` bean trong service sở hữu topic**, để khai báo ownership ở tầng code:

| Service | Config class | Khai báo topic |
| --- | --- | --- |
| payment-service | `OutboxMessagingConfig` | `PAYMENT_EVENTS`, `REFUND_EVENTS`, `DEAD_LETTER` |
| risk-service | `KafkaConsumerConfig` | `RISK_EVENTS`, `DEAD_LETTER` |
| account-ledger-service | `KafkaConsumerConfig` | `ACCOUNT_EVENTS`, `LEDGER_EVENTS`, `DEAD_LETTER` |
| notification-service | `KafkaConsumerConfig` | `DEAD_LETTER` |

Tất cả đều `partitions(3).replicas(1)`. Replica 1 vì local chỉ có 1 broker — nhiều hơn sẽ không khởi động được.

## 8. Envelope: mỗi message trên wire trông như thế nào

Mọi message PayFlow đều dùng cùng một envelope: [`EventEnvelope.java`](../../libs/event-contracts/src/main/java/com/payflow/events/EventEnvelope.java).

```java
public record EventEnvelope<T>(
        UUID eventId,          // định danh ổn định, là dedup key cho consumer
        String eventType,      // "payment.created"
        int eventVersion,      // schema version của data
        String aggregateType,  // "PAYMENT"
        String aggregateId,    // paymentId dạng string; ĐỒNG THỜI là Kafka key
        String correlationId,  // truy vết ngược về HTTP request gốc
        String causationId,    // eventId của event đã gây ra event này; null nếu nguyên nhân là HTTP request
        String producer,       // "payment-service"
        Instant occurredAt,    // thời điểm sự thật nghiệp vụ xảy ra, KHÔNG phải thời điểm publish
        T data)                // payload có version
```

Một message thật trên topic `payflow.payment.events.v1` sẽ là JSON kiểu:

```json
{
  "eventId": "9f1c...",
  "eventType": "payment.created",
  "eventVersion": 1,
  "aggregateType": "PAYMENT",
  "aggregateId": "3b7e...",
  "correlationId": "c-8a2f...",
  "causationId": null,
  "producer": "payment-service",
  "occurredAt": "2026-08-04T09:15:22.481Z",
  "data": {
    "paymentId": "3b7e...",
    "merchantId": "...",
    "customerId": "...",
    "sourceAccountId": "...",
    "amount": "100.0000",
    "currency": "VND",
    "createdAt": "2026-08-04T09:15:22.481Z"
  }
}
```

Ba trường đáng chú ý:

**`eventId` được sinh khi INSERT dòng outbox, không phải khi publish.** Đây là điều kiện sống còn của toàn bộ chuỗi at-least-once. Javadoc nói rõ:

> một đợt republish sau khi crash phải mang cùng `eventId` với lượt gửi có thể đã tới broker, nếu không consumer inbox không thể nhận biết duplicate và toàn bộ chuỗi at-least-once sẽ mất hiệu lực.

Nếu `eventId` được sinh lúc gửi, mọi lần retry sẽ trông như một event mới, và inbox chống trùng trở nên vô dụng.

**`aggregateId` vừa nằm trong body vừa là Kafka key.** Router bắt buộc hai giá trị này phải khớp (mục 10).

**`causationId` khác `correlationId`.** `correlationId` cho biết request HTTP nào gây ra chuỗi này. `causationId` cho biết event nào ngay trước đó gây ra event này. Có cả hai thì bạn dựng lại được cây nhân quả của Saga, không chỉ biết "cùng một request".

## 9. Kafka headers

Ngoài body, mỗi record mang 4 header ([`EventHeaders.java`](../../libs/event-contracts/src/main/java/com/payflow/events/EventHeaders.java)):

| Header | Giá trị |
| --- | --- |
| `X-Correlation-Id` | trùng tên với HTTP header, để grep được xuyên suốt |
| `X-Event-Id` | dedup key, consumer loại trùng trước cả khi parse body |
| `X-Event-Type` | để consumer bỏ qua event không thuộc mình mà không phải parse |
| `X-Event-Version` | schema version |

Bốn giá trị này **trùng lặp với body — và đó là chủ ý**. Header cho phép đọc topic bằng console consumer mà thấy ngay message là gì, không cần schema.

Nhưng có một quy tắc: **body là nguồn có thẩm quyền, không phải header**. Header không được schema check, nên consumer không bao giờ được tin header hơn envelope.

## 10. Producer side: từ DB đến Kafka record

PayFlow **không service nào gọi `kafkaTemplate.send()` trong transaction nghiệp vụ**. Đây là ràng buộc cứng.

### 10.1. Bước ghi outbox — trong transaction nghiệp vụ

[`JpaOutboxAppender.java`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/persistence/JpaOutboxAppender.java) ghi một dòng vào `payment.outbox_events` bằng `EntityManager` của caller, tức là **cùng transaction với business write**.

```java
UUID eventId = UUID.randomUUID();
EventEnvelope<T> envelope = EventEnvelope.of(
        eventId, type, aggregateId, correlationId(), PRODUCER, occurredAt, data);
persist(topic, envelope);
```

`correlationId` được lấy từ MDC chứ không truyền qua signature, vì nó là request context, không phải business input.

Cấu trúc bảng (`V2__payment_intake.sql`):

```sql
CREATE TABLE payment.outbox_events (
    id               UUID         NOT NULL,   -- = eventId
    aggregate_type   VARCHAR(100) NOT NULL,
    aggregate_id     VARCHAR(100) NOT NULL,   -- = Kafka key
    event_type       VARCHAR(150) NOT NULL,
    event_version    INTEGER      NOT NULL,
    topic            VARCHAR(255) NOT NULL,   -- topic đích
    payload          JSONB        NOT NULL,   -- toàn bộ envelope đã serialize
    headers          JSONB        NOT NULL,   -- 4 header
    status           VARCHAR(30)  NOT NULL,   -- PENDING / PROCESSING / PUBLISHED / FAILED
    attempt_count    INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    lock_owner       VARCHAR(100),
    lock_until       TIMESTAMPTZ,
    last_error       VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL,
    published_at     TIMESTAMPTZ
);
```

`payload` là **bytes bất biến**. Publisher không dựng lại envelope, nó gửi đúng chuỗi đã lưu.

### 10.2. Bước publish — ngoài transaction nghiệp vụ

[`OutboxPollingJob`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/OutboxPollingJob.java) chạy mỗi 500ms:

```java
@Scheduled(fixedDelayString = "${payflow.outbox.poll-interval:500ms}")
void poll() {
    OutboxBatchResult result = publisher.publishAvailable(owner.value());
    ...
}
```

[`PublishOutboxHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/PublishOutboxHandler.java) thực hiện protocol **claim → publish → conditional mark**, và **không giữ transaction DB trong lúc gọi I/O Kafka**:

1. `store.claim(owner, lease, batchSize)` — chiếm quyền xử lý một batch.
2. Với từng event: `transport.publish(event)`.
3. Nếu gửi thành công: `store.markPublished(eventId, owner)`.
4. Nếu lỗi: tính backoff, `markRetry(...)` hoặc `markFailed(...)` khi cạn attempt.

### 10.3. Claim bằng lease, không bằng `SELECT FOR UPDATE` dài

[`JdbcOutboxLeaseStore`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/JdbcOutboxLeaseStore.java) dùng hai query.

Query 1 — thu hồi lease chết (worker crash giữa đường):

```sql
update payment.outbox_events
   set lock_owner = :owner,
       lock_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
       attempt_count = attempt_count + 1
 where id in (select id from payment.outbox_events
               where status = 'PROCESSING' and lock_until < clock_timestamp()
               order by created_at limit :limit
               for update skip locked)
```

Query 2 — claim event mới đến hạn:

```sql
update payment.outbox_events
   set status = 'PROCESSING', lock_owner = :owner,
       lock_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
       attempt_count = attempt_count + 1
 where id in (select id from payment.outbox_events
               where status = 'PENDING' and next_attempt_at <= clock_timestamp()
               order by created_at limit :limit
               for update skip locked)
```

Hai chi tiết quan trọng:

- `for update skip locked`: nhiều instance publisher chạy song song sẽ **bỏ qua** dòng instance khác đang giữ thay vì xếp hàng chờ. Không có deadlock, không có nghẽn.
- `clock_timestamp()`: predicate về thời gian dùng **đồng hồ database**, không phải đồng hồ của worker. Các worker chạy trên host khác nhau và đồng hồ của chúng không phải nguồn tin cậy chung.

Mark cũng có điều kiện, để một worker đã mất lease không ghi đè kết quả của worker khác:

```sql
update payment.outbox_events set status = 'PUBLISHED', ...
 where id = :id and status = 'PROCESSING' and lock_owner = :owner
```

Nếu update trả 0 dòng, `PublishOutboxHandler` đếm vào `lostClaims` và log warning.

### 10.4. Giữ thứ tự khi một event lỗi

Đây là logic tinh tế nhất của publisher:

```java
Map<String, Instant> blockedAggregates = new HashMap<>();

for (ClaimedOutboxEvent event : events) {
    if (blockedAggregates.containsKey(event.aggregateId())) {
        Instant retryAt = blockedAggregates.get(event.aggregateId());
        store.markRetry(event.eventId(), owner, retryAt, "BlockedByEarlierAggregateEvent");
        continue;
    }
    ...
    } catch (Exception failure) {
        Instant retryAt = clock.instant().plus(policy.backoffFor(event.attemptCount()));
        blockedAggregates.put(event.aggregateId(), retryAt);
        ...
    }
}
```

Nếu event thứ nhất của payment `abc-123` gửi lỗi, mọi event sau của **cùng payment đó** trong batch bị hoãn sang cùng thời điểm retry, không được gửi trước.

Vì sao cần: giả sử batch có `account.reserve.requested` rồi `ledger.post-payment.requested` của cùng payment. Nếu cái đầu lỗi mà cái sau vẫn gửi, Ledger sẽ nhận yêu cầu ghi sổ cho một khoản tiền chưa bao giờ được giữ. Kafka giữ thứ tự trong partition, nhưng nó không cứu bạn nếu producer tự gửi sai thứ tự.

Lưu ý: chỉ payment cùng `aggregateId` bị chặn. Các payment khác trong batch vẫn chạy bình thường.

### 10.5. Tạo Kafka record

[`KafkaOutboxTransport`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/KafkaOutboxTransport.java):

```java
ProducerRecord<String, String> record =
        new ProducerRecord<>(event.topic(), event.aggregateId(), event.payload());
event.headers().forEach((name, value) ->
        record.headers().add(name, value.getBytes(StandardCharsets.UTF_8)));

kafka.send(record).get(properties.deliveryTimeout().toMillis(), TimeUnit.MILLISECONDS);
```

- topic = cột `topic` của dòng outbox
- key = `aggregate_id` = `paymentId` → per-payment ordering
- value = cột `payload` nguyên vẹn
- `.get(timeout)` = chờ broker xác nhận, **đồng bộ**. Không fire-and-forget.

### 10.6. Producer config

Trong `application.yml` của payment-service:

```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
      acks: all
      properties:
        enable.idempotence: true
        delivery.timeout.ms: ${PAYFLOW_KAFKA_DELIVERY_TIMEOUT_MS:60000}
```

| Setting | Nghĩa |
| --- | --- |
| `acks: all` | Chờ **mọi replica trong ISR** xác nhận, không chỉ leader. Với 1 broker thì hiệu ứng nhỏ, nhưng đây là config đúng khi scale lên. `acks: 1` sẽ mất message nếu leader chết trước khi replicate. |
| `enable.idempotence: true` | Producer gắn sequence number vào mỗi record. Nếu retry nội bộ do lỗi mạng, broker nhận diện và không ghi trùng. Chỉ chống trùng **trong một producer session**, không chống trùng khi ứng dụng chủ động gửi lại từ outbox. |
| `StringSerializer` | Payload là JSON string, không dùng Avro/Protobuf. Đổi lại: đọc topic bằng console consumer là thấy ngay. |
| `delivery.timeout.ms` | 60s. Ghim vào cùng biến môi trường với `payflow.outbox.delivery-timeout` để lease guard và network timeout không mâu thuẫn ngầm. |

### 10.7. Outbox config

```yaml
payflow:
  outbox:
    enabled: true
    poll-interval: 500ms   # bao lâu quét outbox một lần
    batch-size: 100        # mỗi lần claim tối đa 100 dòng
    lease: 120s            # lease dài bao lâu trước khi worker khác thu hồi
    max-attempts: 10       # quá số này thì FAILED, cần can thiệp tay
    max-backoff: 300s      # trần backoff
    delivery-timeout: 60000ms
```

Metrics phát ra từ `OutboxPollingJob`:

```text
payflow.outbox.published          (counter)
payflow.outbox.publish.failed     (counter)
payflow.outbox.reclaimed          (counter)
payflow.outbox.failed.terminal    (counter)
payflow.outbox.claim.lost         (counter)
payflow.outbox.pending.age.seconds (gauge)  <- cái này quan trọng nhất
```

`pending.age.seconds` tăng dần nghĩa là outbox đang tắc: event đã ghi DB nhưng không ra được Kafka.

## 11. Consumer side: từ Kafka record đến DB

Mọi consumer PayFlow đều theo cùng kiến trúc ba lớp:

```text
KafkaListener      <- biên Kafka, mỏng, chỉ route + ack
   |
EventRouter        <- parse eventType, deserialize đúng payload, validate key
   |
Handler            <- transaction: inbox marker + business change + outbox mới
```

### 11.1. Listener: ack chỉ sau khi transaction xong

[`PaymentWorkflowKafkaListener`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/PaymentWorkflowKafkaListener.java):

```java
@KafkaListener(
        id = "payment-risk-events",
        groupId = GROUP_ID,
        topics = PayFlowTopics.RISK_EVENTS,
        autoStartup = "${payflow.workflow-consumer.enabled:true}")
void onRiskEvent(String payload,
        @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
        Acknowledgment acknowledgment) {
    consume(key, payload, acknowledgment);
}

private void consume(String key, String payload, Acknowledgment acknowledgment) {
    try {
        PaymentWorkflowEventRouter.RouteResult result = router.route(key, payload);
        metrics.counter("payflow.payment.workflow.consumer",
                "outcome", result.name().toLowerCase()).increment();
        acknowledgment.acknowledge();     // <- CHỈ ở đây, sau khi router trả về
        ...
    } catch (RuntimeException failure) {
        metrics.counter("payflow.payment.workflow.consumer", "outcome", "failed").increment();
        throw failure;                     // <- không ack; Kafka sẽ giao lại
    }
}
```

Thứ tự `router.route(...)` → `acknowledge()` là điều làm nên at-least-once. Nếu handler throw, offset không được commit, message sẽ đến lại.

Config bật điều này:

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false     # KHÔNG để Kafka tự commit theo thời gian
      auto-offset-reset: earliest   # consumer group mới đọc từ đầu log
    listener:
      ack-mode: manual_immediate    # ack khi code gọi acknowledge()
```

`enable-auto-commit: false` là bắt buộc. Nếu bật, Kafka commit offset theo chu kỳ bất kể code đã xử lý xong chưa → thành at-most-once → mất event tiền.

### 11.2. Bảng consumer group đầy đủ

| Service | `group.id` | Listener id | Topic nghe |
| --- | --- | --- | --- |
| payment-service | `payment-saga-orchestrator-v1` | `payment-risk-events` | `payflow.risk.events.v1` |
| payment-service | `payment-saga-orchestrator-v1` | `payment-account-events` | `payflow.account.events.v1` |
| payment-service | `payment-saga-orchestrator-v1` | `payment-ledger-events` | `payflow.ledger.events.v1` |
| risk-service | `risk-payment-created-v1` | `risk-payment-events` | `payflow.payment.events.v1` |
| account-ledger-service | `account-ledger-workflow-v1` | `account-ledger-payment-commands` | `payflow.payment.events.v1` |
| account-ledger-service | `account-ledger-workflow-v1` | `account-ledger-refund-requests` | `payflow.refund.events.v1` |
| notification-service | `notification-outcome-v1` | `notification-payment-outcomes` | `payflow.payment.events.v1` |
| notification-service | `notification-outcome-v1` | `notification-refund-outcomes` | `payflow.refund.events.v1` |

Ba group cùng đọc `payflow.payment.events.v1`: `risk-payment-created-v1`, `account-ledger-workflow-v1`, `notification-outcome-v1`. Mỗi group có offset riêng nên cả ba đều nhận đủ mọi message — không ai "lấy mất" của ai.

Hậu quả kèm theo: mỗi group cũng nhận **mọi** message trên topic đó, kể cả loại nó không quan tâm. Đó là việc của router.

### 11.3. Router: parse eventType trước, deserialize sau

[`PaymentWorkflowEventRouter`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/PaymentWorkflowEventRouter.java):

```java
Object rawType = objectMapper.readValue(payload, JSON_OBJECT).get("eventType");
if (!(rawType instanceof String eventType) || eventType.isBlank()) {
    throw new IllegalArgumentException("Kafka eventType must not be blank");
}

EventProcessingResult result = switch (eventType) {
    case "risk.assessment.completed" -> handler.handleRiskAssessment(
            keyed(kafkaKey, objectMapper.readValue(payload, RISK_ASSESSMENT)));
    case "account.funds-reserved" -> handler.handleFundsReserved(
            keyed(kafkaKey, objectMapper.readValue(payload, FUNDS_RESERVED)));
    ...
    default -> null;                       // <- event không thuộc mình
};
return result == null ? RouteResult.IGNORED : RouteResult.valueOf(result.name());
```

Ba điều đáng chú ý:

**Parse hai lần là chủ ý.** Lần đầu đọc thành `Map` chỉ để lấy `eventType`. Lần hai deserialize đúng generic type `EventEnvelope<RiskAssessmentCompletedData>`. Nếu deserialize thẳng vào một type cố định, một event lạ sẽ nổ lỗi parse thay vì được bỏ qua sạch sẽ.

**`default -> null` → `IGNORED`, không phải lỗi.** Payment Saga đọc `payflow.account.events.v1` nhưng chỉ xử lý 5 loại account event; loại khác bị bỏ qua và **vẫn được ack**. Không ack sẽ khiến partition tắc vì một message vô hại.

**`keyed()` bắt buộc key khớp `aggregateId`:**

```java
private static <T> EventEnvelope<T> keyed(String kafkaKey, EventEnvelope<T> event) {
    if (kafkaKey == null || !kafkaKey.equals(event.aggregateId())) {
        throw new IllegalArgumentException(
                "Kafka key must equal envelope aggregateId for Payment workflow ordering");
    }
    return event;
}
```

Vì sao cần kiểm tra: toàn bộ giả định per-payment ordering dựa trên "key = paymentId". Nếu một producer nào đó gửi key sai, event của payment `abc` có thể rơi vào partition của payment `xyz`, và thứ tự bị phá mà không có triệu chứng nào. Check này biến bug ngầm thành lỗi ngay.

Router của các service khác cùng pattern:

| Router | Xử lý event type |
| --- | --- |
| `PaymentWorkflowEventRouter` | `risk.assessment.completed`, `account.funds-reserved`, `account.funds-reservation-failed`, `account.funds-captured`, `account.funds-released`, `ledger.payment-posted`, `ledger.payment-posting-failed`, `ledger.refund-posted`, `ledger.refund-posting-failed`, `account.refund-credited` |
| `RiskPaymentEventRouter` | chỉ `payment.created` |
| `AccountLedgerWorkflowEventRouter` | `account.reserve.requested`, `account.capture.requested`, `account.release.requested`, `ledger.post-payment.requested`, `refund.requested`, `account.refund-credit.requested` |
| `NotificationOutcomeEventRouter` | `payment.succeeded`, `payment.failed`, `refund.succeeded`, `refund.failed` |

### 11.4. Handler: inbox marker + business change trong một transaction

Đây là chỗ chống trùng thật sự. [`JdbcProcessedEventStore`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/JdbcProcessedEventStore.java):

```sql
insert into payment.processed_events (
    event_id, consumer_name, event_type, aggregate_id, processed_at)
values (:eventId, :consumerName, :eventType, :aggregateId, :processedAt)
on conflict (event_id, consumer_name) do nothing
```

`@Transactional(propagation = Propagation.MANDATORY)` — method này **bắt buộc** phải được gọi bên trong transaction của caller. Nếu ai đó gọi nó ngoài transaction, Spring throw. Marker và business change không được phép ở hai transaction khác nhau.

Trong [`HandlePaymentWorkflowEventHandler`](../../services/payment-service/src/main/java/com/payflow/payment/application/handler/HandlePaymentWorkflowEventHandler.java):

```java
Instant processedAt = clock.instant();
return transactions.execute(status -> {
    boolean isNew = inbox.recordIfNew(new IncomingEventIdentity(
            event.eventId(), CONSUMER_NAME, event.eventType(),
            event.aggregateId(), processedAt));
    if (!isNew) {
        return EventProcessingResult.DUPLICATE;    // <- đã xử lý rồi, thoát sớm
    }

    // load Payment + Saga, apply domain mutation, update
    ...
    outbox.appendCausedBy(change.type(), PayFlowTopics.PAYMENT_EVENTS,
            paymentId.toString(), processedAt, change.data(), event);
    return EventProcessingResult.PROCESSED;
});
```

Toàn bộ nằm trong một transaction PostgreSQL:

```text
BEGIN
  insert processed_events (event_id, consumer_name)  -- inbox gate
  update payment ...                                  -- business change
  update payment_sagas ...                            -- saga state
  insert outbox_events ...                            -- event tiếp theo
COMMIT
-- rồi mới acknowledge() Kafka
```

Nếu bất kỳ bước nào lỗi, **tất cả** rollback, kể cả inbox marker. Kafka không được ack, message đến lại, và lần này inbox trống nên xử lý lại từ đầu. Không có trạng thái nửa vời.

Nếu commit thành công nhưng crash trước khi ack: message đến lại, inbox marker đã có → `DUPLICATE` → không apply lần hai. **Đây chính là chỗ "at-least-once + idempotent" hoạt động.**

`consumer_name` nằm trong khóa chống trùng, không chỉ `event_id`. Vì cùng một event `payment.created` được **nhiều** consumer xử lý — Risk xử lý nó, Notification cũng nhìn topic đó. Nếu khóa chỉ là `event_id`, consumer thứ hai sẽ bị coi là trùng và không bao giờ chạy.

Consumer name trong payment-service:

| Consumer name | Xử lý |
| --- | --- |
| `payment-saga-orchestrator-v1` | risk + account + ledger event của payment flow |
| `payment-refund-orchestrator-v1` | ledger refund + account refund credit event |

Ở các service khác, `consumer_name` được đặt **theo từng handler**, không theo group:

| Service | `consumer_name` |
| --- | --- |
| risk-service | `risk-payment-created-v1` |
| notification-service | `notification-outcome-v1` |
| account-ledger-service | `account-reserve-funds-v1`, `account-capture-funds-v1`, `account-release-funds-v1`, `account-refund-credit-v1`, `ledger-post-payment-v1`, `ledger-refund-requested-v1` |

Account/Ledger chia nhỏ tới mức handler vì đây là một deployable chứa **hai bounded context**: Account và Ledger có thể cùng nhìn thấy một event, và mỗi bên phải chống trùng độc lập với bên kia.

### 11.5. Hai loại duplicate

Kết quả xử lý là enum `EventProcessingResult` — và **enum này không giống nhau ở mọi service**:

| Kết quả | Nghĩa | Service nào có |
| --- | --- | --- |
| `PROCESSED` | Event mới, đã apply | tất cả |
| `DUPLICATE` | **Transport duplicate** — cùng `eventId`, Kafka giao lại | tất cả |
| `BUSINESS_DUPLICATE` | **Business duplicate** — `eventId` khác nhưng sự thật nghiệp vụ đã có (ví dụ: đã có risk assessment cho `paymentId` này, hoặc đã có notification cho business reference này) | risk, account-ledger, notification — **không có ở payment-service** |

Payment Service chỉ có `PROCESSED` và `DUPLICATE`, vì nó là orchestrator: nó không "nhận một sự thật nghiệp vụ mới" mà chỉ chuyển trạng thái Saga. Việc chống áp dụng hai lần đã nằm trong chính state machine của Saga (một bước đã qua thì không nhận lại kết quả của bước đó), nên không cần thêm một hạng mục duplicate riêng.

Phân biệt hai loại này quan trọng: `DUPLICATE` là chuyện bình thường của Kafka. `BUSINESS_DUPLICATE` nghĩa là có ai đó republish một sự thật nghiệp vụ với eventId mới — thường là do `RecoverOverdueSagasHandler` phát lại command (xem mục 13) — vẫn phải chặn, nhưng đáng để cảnh giác nếu tăng bất thường.

Test chứng minh cả ba trong `NotificationWorkflowPersistenceIT`:

```java
assertThat(handler.handle(factory.paymentSucceeded(event)))
        .isEqualTo(EventProcessingResult.PROCESSED);
assertThat(handler.handle(factory.paymentSucceeded(event)))
        .isEqualTo(EventProcessingResult.DUPLICATE);
assertThat(handler.handle(factory.paymentSucceeded(republishedBusinessFact)))
        .isEqualTo(EventProcessingResult.BUSINESS_DUPLICATE);

// vẫn chỉ đúng 1 notification
assertThat(count("where business_reference_id = ?", event.data().paymentId())).isEqualTo(1);
```

## 12. Khi consumer xử lý không được: retry và dead-letter

Nếu handler throw, message không được ack. Nếu để tự nhiên, Kafka giao lại mãi mãi và **partition đó tắc vĩnh viễn** — gọi là poison message. Một message hỏng chặn mọi payment sau nó trong cùng partition.

PayFlow chặn việc này bằng `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`.

[`PaymentWorkflowConsumerConfig`](../../services/payment-service/src/main/java/com/payflow/payment/infrastructure/messaging/PaymentWorkflowConsumerConfig.java):

```java
DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
        kafkaTemplate,
        (record, failure) -> new TopicPartition(PayFlowTopics.DEAD_LETTER, record.partition()));
recoverer.setFailIfSendResultIsError(true);

DefaultErrorHandler errorHandler = new DefaultErrorHandler(
        recoverer,
        new FixedBackOff(properties.retryBackoff().toMillis(), properties.maxRetries()));
errorHandler.setCommitRecovered(true);
```

Diễn giải:

1. Handler throw → chờ `retryBackoff` (1s) → thử lại. Tối đa `maxRetries` (3) lần.
2. Vẫn lỗi → gửi record gốc sang `payflow.dead-letter.v1`, **giữ nguyên số partition**.
3. `setCommitRecovered(true)` → sau khi đẩy sang DLT thì commit offset, partition được giải phóng.
4. `setFailIfSendResultIsError(true)` → nếu gửi DLT cũng lỗi thì báo lỗi thay vì âm thầm bỏ message.

Giữ nguyên partition khi sang DLT là để khi replay, các message của cùng một payment vẫn giữ đúng thứ tự tương đối.

Cấu hình theo service:

| Service | Backoff | Max retries |
| --- | --- | --- |
| payment-service | `payflow.workflow-consumer.retry-backoff` (default 1s) | `payflow.workflow-consumer.max-retries` (default 3) |
| risk-service | `FixedBackOff(1_000L, 3L)` hardcoded | 3 |
| account-ledger-service | `FixedBackOff(1_000L, 3L)` hardcoded | 3 |
| notification-service | `FixedBackOff(1_000L, 3L)` hardcoded | 3 |

Có message trong `payflow.dead-letter.v1` nghĩa là **cần con người xem**. Quy trình xử lý ở [`runbooks/payment-workflow-dlt.md`](../runbooks/payment-workflow-dlt.md).

## 13. Ba tầng retry — đừng nhầm lẫn

PayFlow có ba cơ chế retry hoàn toàn khác nhau, ở ba chỗ khác nhau. Nhầm chúng là nguồn lẫn lộn lớn nhất.

| | Retry ở đâu | Cứu chuyện gì | Cạn thì sao |
| --- | --- | --- | --- |
| **Outbox retry** | Producer, `PublishOutboxHandler` | DB đã commit nhưng **gửi Kafka lỗi** | `status = FAILED`, cần requeue tay ([runbook outbox](../runbooks/outbox-recovery.md)) |
| **Consumer retry** | `DefaultErrorHandler` | **Consumer xử lý lỗi** (DB lỗi tạm, bug, dữ liệu hỏng) | Đẩy sang `payflow.dead-letter.v1` |
| **Saga deadline retry** | `RecoverOverdueSagasHandler` | **Không có phản hồi nào cả** — event mất, service chết, hoặc consumer chưa bao giờ nhận | Compensation, hoặc `MANUAL_REVIEW_REQUIRED` |

Tầng thứ ba là tầng Kafka không giải quyết được. Giả sử Payment phát `account.reserve.requested` thành công, nhưng Account Service đang down và message hết retention trước khi nó sống lại. Không có exception nào để catch — chỉ là **im lặng**.

Vì vậy mỗi Saga step có deadline. `RecoverOverdueSagasHandler` quét saga quá hạn và phát lại command:

| Saga step đang chờ | Event được phát lại |
| --- | --- |
| `RISK_ASSESSMENT` | `payment.created` |
| `RESERVE_FUNDS` | `account.reserve.requested` |
| `POST_LEDGER` | `ledger.post-payment.requested` |
| `CAPTURE_FUNDS` | `account.capture.requested` |
| `RELEASE_FUNDS` | `account.release.requested` |

Config: `payflow.saga-recovery.step-timeout: 30s`, `max-retries: 3`, `poll-interval: 1s`, `batch-size: 50`.

Command phát lại là một dòng outbox **mới** với `eventId` mới. Nên inbox không nhận diện nó là duplicate. Việc chống trùng ở đây thuộc về **business idempotency** trong handler nghiệp vụ: `HandleReserveFundsRequestedHandler` phải nhận ra reservation cho payment này đã tồn tại và không giữ tiền lần hai. Inbox chống duplicate transport; business check chống duplicate command.

## 14. Trace một payment qua Kafka

Với `paymentId = 3b7e...`, đây là toàn bộ traffic Kafka, theo thứ tự:

| # | Topic | Key | eventType | Producer | Consumer group nhận và xử lý |
| --- | --- | --- | --- | --- | --- |
| 1 | `payment.events.v1` | `3b7e...` | `payment.created` | payment | `risk-payment-created-v1` |
| 2 | `risk.events.v1` | `3b7e...` | `risk.assessment.completed` | risk | `payment-saga-orchestrator-v1` |
| 3 | `payment.events.v1` | `3b7e...` | `account.reserve.requested` | payment | `account-ledger-workflow-v1` |
| 4 | `account.events.v1` | `3b7e...` | `account.funds-reserved` | account-ledger | `payment-saga-orchestrator-v1` |
| 5 | `payment.events.v1` | `3b7e...` | `ledger.post-payment.requested` | payment | `account-ledger-workflow-v1` |
| 6 | `ledger.events.v1` | `3b7e...` | `ledger.payment-posted` | account-ledger | `payment-saga-orchestrator-v1` |
| 7 | `payment.events.v1` | `3b7e...` | `account.capture.requested` | payment | `account-ledger-workflow-v1` |
| 8 | `account.events.v1` | `3b7e...` | `account.funds-captured` | account-ledger | `payment-saga-orchestrator-v1` |
| 9 | `payment.events.v1` | `3b7e...` | `payment.succeeded` | payment | `notification-outcome-v1` |

Chín message Kafka cho một payment thành công. Cùng key nên **cả 9 đều cùng partition trên topic của chúng**.

Chuỗi `causationId` nối chúng lại: message #2 có `causationId` = `eventId` của #1, #3 trỏ về #2, và cứ thế. `correlationId` thì **giống nhau ở cả 9** vì cùng một HTTP request sinh ra.

Chú ý các group nhận nhưng bỏ qua:

- `risk-payment-created-v1` nhận cả #3, #5, #7, #9 (cùng topic) nhưng router trả `IGNORED`.
- `account-ledger-workflow-v1` nhận #1, #9 nhưng `IGNORED`.
- `notification-outcome-v1` nhận #1, #3, #5, #7 nhưng `IGNORED`.

Traffic bị "lãng phí" này là cái giá của việc gom event theo bounded context thay vì một topic cho mỗi event type. Đổi lại: ít topic hơn, ownership rõ ràng, và thêm consumer mới không cần producer thay đổi gì.

## 15. Kafka trong Docker Compose

```yaml
kafka:
  image: apache/kafka:4.3.1
  environment:
    KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
    KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:19092,HOST://localhost:${KAFKA_PORT}
    KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
    KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
    KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
  ports:
    - "127.0.0.1:${KAFKA_PORT}:9092"
```

**Hai advertised listener** là chi tiết hay gây khó hiểu. Kafka hoạt động thế này: client kết nối tới bootstrap server, broker trả về **địa chỉ thật** để client kết nối tiếp. Nếu chỉ khai báo một địa chỉ:

- Chỉ có `kafka:19092` → service trong Docker chạy được, app chạy từ IDE trên host **không** resolve được `kafka`.
- Chỉ có `localhost:9092` → app trên host chạy được, service trong container gọi `localhost` là chính nó → lỗi.

Khai báo cả hai listener giải quyết cả hai hướng.

**Replication factor 1** là lựa chọn duy nhất hợp lệ với 1 broker. Đặt 3 sẽ khiến broker không khởi tạo được internal topic.

**Port bind `127.0.0.1:`** — chỉ localhost, không mở ra mạng ngoài.

Mọi service đều `depends_on: kafka-init: condition: service_completed_successfully`, nên không listener nào start trước khi topic tồn tại.

## 16. Debug Kafka

### 16.1. Lệnh CLI

```bash
# Liệt kê topic
docker exec payflow-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:19092 --list

# Xem chi tiết một topic (partition, leader, ISR)
docker exec payflow-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:19092 --describe --topic payflow.payment.events.v1

# Đọc message từ đầu, kèm key và header
docker exec payflow-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 \
  --topic payflow.payment.events.v1 \
  --from-beginning --property print.key=true --property print.headers=true

# Consumer group: LAG là con số quan trọng nhất
docker exec payflow-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:19092 --describe --group payment-saga-orchestrator-v1

# Kiểm tra dead-letter có gì
docker exec payflow-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:19092 --topic payflow.dead-letter.v1 \
  --from-beginning --property print.headers=true
```

**Đọc cột LAG** trong `--describe --group`: lag = số message đã có trong partition mà group chưa xử lý.

- Lag = 0 → consumer bắt kịp.
- Lag ổn định > 0 → consumer chậm hơn producer.
- Lag tăng liên tục → consumer tắc hoặc đã chết.

`kafka-console-consumer` không ảnh hưởng offset của service, vì nó tạo group tạm riêng.

### 16.2. Metrics ứng dụng

| Metric | Đọc như thế nào |
| --- | --- |
| `payflow.outbox.pending.age.seconds` | Tăng → event ghi DB nhưng chưa ra Kafka. Nghi vấn: broker down, producer lỗi. |
| `payflow.outbox.publish.failed` | Tăng → gửi Kafka đang lỗi. |
| `payflow.outbox.failed.terminal` | Tăng → có event cạn 10 lần thử, đã `FAILED`, **cần requeue tay**. |
| `payflow.outbox.claim.lost` | Tăng nhiều → nhiều publisher tranh nhau hoặc lease quá ngắn. |
| `payflow.payment.workflow.consumer{outcome=failed}` | Tăng → handler đang throw, sắp có message vào DLT. |
| `payflow.payment.workflow.consumer{outcome=duplicate}` | Số dương nhỏ là bình thường (đúng như thiết kế). Tăng vọt → nghi vấn rebalance liên tục hoặc consumer crash lặp. |
| `payflow.payment.workflow.consumer{outcome=ignored}` | Cao là bình thường: mỗi group nhận cả event không thuộc mình. |

### 16.3. Truy vấn database

```sql
-- Outbox tắc
select status, count(*), min(created_at)
  from payment.outbox_events group by status;

-- Event đã FAILED vĩnh viễn
select id, event_type, aggregate_id, attempt_count, last_error
  from payment.outbox_events where status = 'FAILED' order by created_at;

-- Consumer đã xử lý những gì cho một payment
select consumer_name, event_type, processed_at
  from payment.processed_events
 where aggregate_id = '3b7e...' order by processed_at;
```

Bảng `processed_events` chính là **audit log của Kafka consumer**. Nếu bạn mong đợi một event đã đến mà không có dòng nào ở đây, event chưa bao giờ được xử lý thành công.

`last_error` chỉ chứa tên class exception và message bị cắt còn 500 ký tự — không có payload, không có stack trace, không có cause chain. Ràng buộc bảo mật, xem `PublishOutboxHandler.safeError(...)`.

### 16.4. Thứ tự kiểm tra khi "event không đến"

1. Topic có tồn tại không? → `kafka-topics.sh --list`
2. Event có ra khỏi producer không? → `select status from outbox_events where aggregate_id = ...`
3. Message có trên topic không? → `kafka-console-consumer` từ đầu, tìm `paymentId`
4. Consumer group có đang chạy không? → `kafka-consumer-groups.sh --describe`, xem có member và LAG
5. Consumer có xử lý không? → `select * from processed_events where aggregate_id = ...`
6. Có vào DLT không? → đọc `payflow.dead-letter.v1`
7. Router có bỏ qua không? → xem metric `outcome=ignored` và log debug

## 17. Những kết luận sai thường gặp

**"Kafka nhận rồi tức là xử lý xong rồi."** Sai. Kafka nhận nghĩa là message nằm trong log. Consumer có thể chưa đọc, đang retry, hoặc đã vào DLT. Bằng chứng nghiệp vụ là trạng thái trong PostgreSQL.

**"Offset đã commit tức là business đã commit."** Sai theo hướng ngược lại thì đúng: business commit **trước**, offset commit **sau**. Nếu thấy offset đã tiến, business chắc chắn đã commit. Nhưng thấy offset chưa tiến thì business có thể đã commit rồi (crash giữa commit và ack) — đó chính là trường hợp inbox tồn tại để xử lý.

**"Tăng partition lên là nhanh hơn."** Chỉ khi đang có nhiều instance consumer. 3 partition với 1 instance thì tăng lên 12 partition cũng không giúp gì. Ngoài ra tăng partition **thay đổi hash mapping**, key cũ có thể rơi vào partition mới → phá thứ tự của các payment đang chạy.

**"Bật `enable.idempotence` là hết duplicate."** Không. Nó chỉ chống duplicate do producer tự retry nội bộ trong một session. Duplicate do outbox chủ động gửi lại sau crash, hoặc do Kafka giao lại khi consumer chưa ack, thì nó không chặn. Đó là việc của inbox.

**"Kafka là nơi lưu dữ liệu payment."** Không. Kafka là đường truyền, có retention giới hạn. Nguồn sự thật là database từng service.

**"`payflow.notification.commands.v1` đang được dùng."** Không. Topic đã có tên trong `PayFlowTopics` nhưng luồng hiện tại không produce/consume nó. Notification Service nghe trực tiếp `payment.events.v1` và `refund.events.v1`. Tương tự `payflow.settlement.events.v1` — chưa có settlement-service.

**"Không ack thì cứ để nó retry, an toàn hơn."** Sai và nguy hiểm. Không ack một message không xử lý được sẽ chặn **cả partition**, tức là chặn mọi payment cùng partition đó. Đây là lý do phải có DLT và `setCommitRecovered(true)`.

---

## Đọc tiếp

| Muốn gì | Đọc gì |
| --- | --- |
| Luồng nghiệp vụ payment/refund end-to-end | [current-business-code-flow-guide.md](current-business-code-flow-guide.md) |
| Bản ngắn REST vs Kafka, có sequence diagram | [rest-kafka-flow-guide.md](rest-kafka-flow-guide.md) |
| Contract chi tiết từng event | [`docs/events/`](../events/refund-workflow-v1.md) |
| Xử lý message trong DLT | [runbooks/payment-workflow-dlt.md](../runbooks/payment-workflow-dlt.md) |
| Xử lý outbox `FAILED` | [runbooks/outbox-recovery.md](../runbooks/outbox-recovery.md) |
| Xử lý Saga `MANUAL_REVIEW_REQUIRED` | [runbooks/saga-manual-review.md](../runbooks/saga-manual-review.md) |
| Dependency và config Kafka trong Maven/Spring | [technology-stack-configuration-guide.md](technology-stack-configuration-guide.md) |
