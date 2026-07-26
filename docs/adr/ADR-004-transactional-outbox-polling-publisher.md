# ADR-004: Transactional Outbox với polling publisher, Debezium để sau

- Status: ACCEPTED
- Date: 2026-07-26
- Decision owners: Repository owner (Tien le)
- Supersedes: N/A
- Superseded by: N/A

## Context

Phase 1A phải publish `payment.created` sau khi lưu payment. Hai việc này nằm ở hai
hệ thống khác nhau — PostgreSQL và Kafka — nên không có một transaction nào bao được
cả hai. Nếu ghi database thành công rồi publish thất bại, Saga không bao giờ bắt
đầu và tiền của merchant mắc kẹt ở `PENDING`. Nếu publish thành công rồi transaction
rollback, downstream xử lý một payment không tồn tại.

`.agent/AGENTS.md` §6 đã chốt sẵn hình dạng của lời giải: business write và outbox
insert dùng **một** local transaction, và service **không bao giờ** publish trực
tiếp lên Kafka từ business logic. Điều còn thiếu là cơ chế đưa row outbox lên Kafka.
Spec §8.7 đưa ra hai phương án nhưng không chọn, và roadmap Phase 1A lại yêu cầu một
outbox publisher hoạt động được. Phải chốt trước khi viết migration, vì cơ chế quyết
định luôn cả cột và index của bảng `outbox_events`.

## Decision drivers

- Atomicity: quyết định "sẽ phát event này" phải commit cùng với thay đổi nghiệp vụ
  sinh ra nó, không sớm hơn và không muộn hơn.
- Hạ tầng Phase 1A chỉ có những gì trong `docker-compose.yml`. Thêm Kafka Connect,
  Debezium và `wal_level=logical` là mở rộng scope hạ tầng ở đúng phase mà hạ tầng
  hiện tại **còn chưa từng được start lần nào**.
- `.agent/AGENTS.md` §6: không giữ transaction database mở qua Kafka I/O.
- At-least-once là mục tiêu đã công bố; exactly-once end-to-end thì không
  (spec §8.8). Cơ chế được chọn phải làm duplicate thành chuyện bình thường, có
  kiểm soát, chứ không phải sự cố.
- Thứ tự per-aggregate phải giữ được: Kafka key là `aggregateId` (spec §8.3).
- Giá trị portfolio nằm ở chỗ pattern nhìn thấy được và test được, không nằm ở việc
  vận hành thêm một cluster CDC.

## Options considered

### Option A — Polling publisher (chọn)

Một scheduled job trong chính service đọc row chưa publish, publish lên Kafka, rồi
mark kết quả.

- Ưu: không thêm hạ tầng; toàn bộ logic nằm trong code và test được bằng
  Testcontainers; bảng `outbox_events` là bằng chứng đọc được bằng SQL khi debug;
  publisher restart an toàn vì trạng thái nằm trong database chứ không nằm trong bộ
  nhớ process.
- Nhược: có sàn latency bằng poll interval; publisher dùng chung connection pool với
  request nghiệp vụ; bảng outbox lớn dần và cần retention.
- Rủi ro: một row "poison" (payload không serialize được, topic không tồn tại) có
  thể quay vòng nếu không có backoff và terminal state — đây chính là phần
  [ADR-014](ADR-014-outbox-claim-lease-and-recovery.md) phải giải.

### Option B — Debezium CDC đọc WAL

Debezium theo dõi WAL của PostgreSQL và đẩy thay đổi bảng outbox sang Kafka.

- Ưu: latency thấp hơn, không polling, không tiêu tài nguyên database của service,
  gần với cách nhiều hệ thống production thực sự làm.
- Nhược: cần Kafka Connect + connector + `wal_level=logical` + replication slot;
  một replication slot bị bỏ rơi sẽ giữ WAL lại và làm đầy đĩa PostgreSQL — một
  failure mode vận hành hoàn toàn mới; test integration nặng hơn nhiều.
- Rủi ro: cao ở thời điểm này. Spec đặt Debezium ở Giai đoạn 4 ("Nâng cao tùy
  chọn") và nói rõ chỉ làm sau khi phương án A ổn định.

### Option C — Publish trực tiếp lên Kafka sau khi commit, không có outbox

- Ưu: ít code nhất.
- Nhược: đây là dual write. Crash giữa commit và publish làm mất event vĩnh viễn,
  không có gì trong database ghi lại rằng event đó đáng lẽ phải tồn tại.
- Rủi ro: spec liệt kê chính xác cách làm này trong danh sách anti-pattern phải
  tránh, và `.agent/AGENTS.md` §6 cấm. Loại thẳng.

### Option D — Publish bên trong transaction nghiệp vụ

- Nhược: tệ hơn Option C. Transaction rollback sau khi đã publish thì downstream đã
  nhận một event cho thay đổi không hề tồn tại, và không có cách nào rút lại.
- Loại thẳng.

## Decision

Dùng **Transactional Outbox** với **polling publisher** (spec §8.7 phương án A) cho
mọi service publish event trong PayFlow.

Ba quy tắc bất biến:

1. Business write và `INSERT INTO <schema>.outbox_events` nằm trong cùng một local
   transaction. Không có ngoại lệ, không có "event nhỏ nên publish trực tiếp".
2. Business logic không biết Kafka tồn tại. Nó chỉ ghi outbox. Chỉ publisher nói
   chuyện với broker.
3. Consumer luôn phải idempotent, vì cơ chế này bảo đảm at-least-once chứ không
   phải exactly-once.

Phạm vi áp dụng: Phase 1A implement trong payment-service; mọi producer thêm sau
(account-ledger, risk, refund, settlement) dùng đúng cơ chế này với bảng outbox
riêng trong schema của chính nó.

Debezium hoãn tới Giai đoạn 4 và cần một ADR riêng. Khi đó bảng `outbox_events`
không phải đổi shape, chỉ đổi thành phần đọc nó — đó là một lý do nữa để chọn A
trước.

Điều **không** được suy diễn từ ADR này:

- Không quyết định giao thức claim/lease, backoff hay terminal state — đó là
  [ADR-014](ADR-014-outbox-claim-lease-and-recovery.md).
- Không quyết định inbox/consumer semantics — OD-007 vẫn `OPEN`.
- Không quyết định DLT (Phase 2), retention của bảng outbox, hay schema registry.
- Không phê duyệt bất kỳ event schema cụ thể nào; envelope theo spec §8.2 còn nội
  dung `data` do từng event contract quyết định.

## Consequences

### Positive

- Không mất event: nếu transaction nghiệp vụ commit, event đã nằm trong database và
  sẽ được publish, kể cả khi Kafka đang chết lúc đó.
- Không có event mồ côi: rollback thì row outbox cũng rollback.
- Debug được bằng SQL. "Event này đã phát chưa?" là một câu `SELECT`, không phải một
  cuộc điều tra log.
- Publisher là code trong repo nên test được bằng Testcontainers thật, không cần
  mock broker.

### Negative/trade-offs

- Latency có sàn bằng poll interval. Với Phase 1A điều này chấp nhận được vì API trả
  `202 Accepted` và Saga vốn là bất đồng bộ.
- Publisher tiêu database connection và CPU của chính service. Cần giới hạn batch
  size.
- Duplicate là hành vi bình thường, không phải bug. Mọi consumer phải có inbox.
- Thứ tự global không được bảo đảm; chỉ thứ tự trong cùng partition, tức cùng
  `aggregateId`.
- Bảng outbox lớn dần. Retention chưa được quyết định và phải có ADR trước khi
  chạy dài hạn — ghi nhận đây là nợ đã biết, không phải chi tiết bị bỏ sót.

## Contract and data impact

- API: không thay đổi. Client không thấy outbox. `POST /api/v1/payments` trả `202`
  vì công việc còn lại là bất đồng bộ, không phải vì có outbox.
- Event: payload lưu envelope spec §8.2 (`eventId`, `eventType`, `eventVersion`,
  `aggregateType`, `aggregateId`, `correlationId`, `causationId`, `producer`,
  `occurredAt`, `data`). Kafka key là `aggregateId`. Topic theo bounded context
  (`payflow.payment.events.v1`), phân biệt bằng `eventType`. Vì
  `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`, topic phải được khai báo bằng
  `KafkaAdmin` + `NewTopic` bean, nếu không publish sẽ fail bằng
  `UnknownTopicOrPartitionException`.
- Database/migration: mỗi producer có `outbox_events` **trong schema của chính nó**.
  Không có bảng outbox dùng chung. Cột theo spec §8.6, cộng thêm các cột lease do
  ADR-014 quy định. Không service nào đọc bảng outbox của service khác.
- Security/privacy: row outbox là một bản sao bền vững của event, nên mọi quy tắc
  redaction áp dụng **lúc ghi**, không phải lúc publish. Không đưa API key
  plaintext/hash, token, webhook secret hay PII ngoài allowlist vào `payload` hoặc
  `headers`. Liên quan OD-010 (`OPEN`) cho audit snapshot — outbox không được dùng
  làm đường vòng lách quyết định đó.
- Observability/operations: publish phải giữ nguyên `correlationId` từ transaction
  nghiệp vụ sang envelope và sang Kafka header, để một request HTTP truy được sang
  consumer. Metric bắt buộc theo spec: `outbox pending age` và
  `outbox publish failure`; alert khi oldest pending > 60 giây.

## Rollout and rollback

Forward-only. Bảng outbox và publisher vào cùng một Flyway migration + cùng một
release; không có giai đoạn nào mà business write đã ghi outbox nhưng chưa ai đọc,
ngoài chính thời gian service đang restart.

Rollback nghĩa là tắt scheduler (một config flag), không phải xoá dữ liệu. Row nằm
lại ở `PENDING` và được publish khi bật lại. **Không** bao giờ `DELETE` row chưa
publish để "xử lý cho nhanh": đó là làm mất một event nghiệp vụ đã commit.

Đổi sang Debezium sau này không cần migrate dữ liệu — cùng bảng, khác thành phần
đọc. Cửa sổ chuyển đổi phải tắt polling publisher trước khi bật connector, nếu không
hai bên cùng publish một row.

## Verification

Chưa có test nào cho ADR này chạy — publisher chưa được implement. Các test dưới đây
là điều kiện Definition of Done của Phase 1A, không phải kết quả đã đo:

- Integration test (Testcontainers PostgreSQL + Kafka): `POST /api/v1/payments`
  commit payment và đúng một row outbox trong cùng transaction; đọc lại bằng SQL.
- Rollback test: buộc business logic fail sau khi insert outbox; sau rollback,
  `SELECT count(*) FROM payment.outbox_events` phải bằng 0.
- Publisher test: row `PENDING` được publish lên topic thật và consumer test nhận
  được đúng envelope với key bằng `aggregateId`.
- Kafka-down test: dừng container Kafka, tạo payment, xác nhận API vẫn trả `202` và
  row vẫn `PENDING`; bật lại Kafka và xác nhận row chuyển `PUBLISHED`. Đây là test
  chứng minh giá trị cốt lõi của ADR này.
- Grep gate ở review: không có `KafkaTemplate` nào được inject vào package
  `application` hoặc `domain` của bất kỳ service nào. Chỉ adapter publisher được
  phép.
