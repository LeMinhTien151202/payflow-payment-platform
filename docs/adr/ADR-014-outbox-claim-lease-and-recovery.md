# ADR-014: Outbox claim lease, stale recovery và crash semantics

- Status: ACCEPTED
- Date: 2026-07-26
- Decision owners: Repository owner (Tien le)
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-008

## Context

[ADR-004](ADR-004-transactional-outbox-polling-publisher.md) chọn polling publisher
nhưng để mở phần khó: làm sao nhiều instance cùng đọc một bảng mà không publish trùng
lặp, và làm sao một row không mắc kẹt vĩnh viễn khi process chết giữa lúc publish.

Spec §8.6 khai báo `status` có `PROCESSING` nhưng không nói ai đang xử lý, xử lý từ
bao giờ, và bao lâu thì coi như đã chết. Với đúng shape đó, một `kill -9` đúng thời
điểm để lại một row `PROCESSING` mà không instance nào dám nhặt lại — event nghiệp vụ
đã commit vào database nhưng sẽ không bao giờ tới Kafka, và không có gì báo động
ngoài việc payment đứng im. `.docs/OPEN_DECISIONS.md` OD-008 ghi nhận đúng khoảng
trống này và chặn implementation của publisher.

Ràng buộc thứ hai làm bài toán khó hơn: `.agent/AGENTS.md` §6 không cho giữ
transaction database mở qua Kafka I/O. Cách làm ngây thơ nhất — `SELECT ... FOR
UPDATE SKIP LOCKED`, publish, `UPDATE`, commit — là đúng về mặt loại trừ lẫn nhau
nhưng vi phạm ràng buộc này, nên phải tìm cách khác.

## Decision drivers

- Một row không được publish bởi hai instance **đồng thời**; và không được mắc kẹt
  sau crash. Hai yêu cầu này kéo về hai phía ngược nhau.
- Không giữ transaction qua network I/O. Một broker chậm không được biến thành
  transaction `idle in transaction` và bloat.
- At-least-once là hợp đồng. Duplicate được phép; mất event thì không.
- Mọi failure window phải được nêu tên và có hành vi xác định, kể cả window mà kết
  quả là duplicate.
- Vận hành phải nhìn thấy được: có bao nhiêu row đang chờ, chờ bao lâu, đã reclaim
  bao nhiêu lần.
- Row poison không được quay vòng vô hạn.

## Options considered

### Option A — Giữ `FOR UPDATE SKIP LOCKED` qua suốt lần publish

Một transaction: select-lock, publish, update, commit.

- Ưu: đơn giản nhất; row lock của PostgreSQL tự lo loại trừ lẫn nhau; crash thì lock
  tự nhả và row về nguyên trạng, không cần lease.
- Nhược: vi phạm `.agent/AGENTS.md` §6. Kafka send chậm hay treo sẽ giữ row lock và
  một database connection suốt thời gian đó; `delivery.timeout.ms` mặc định 120 giây
  nghĩa là transaction có thể mở 2 phút. Với batch 100 row thì đó là 100 row bị lock.
- Rủi ro: autovacuum không dọn được, connection pool cạn, và triệu chứng nhìn giống
  vấn đề database chứ không giống vấn đề Kafka.

### Option B — Lease claim: claim trong transaction ngắn, publish ngoài transaction (chọn)

Transaction ngắn thứ nhất đánh dấu row là của mình kèm thời hạn; publish xảy ra khi
không có transaction nào mở; transaction ngắn thứ hai mark kết quả có điều kiện.

- Ưu: tôn trọng §6; không có lock nào tồn tại qua network I/O; `PROCESSING` trở nên
  có nghĩa vì nó có owner và deadline; reclaim là một câu `UPDATE` theo
  `lock_until < now()`, không cần coordinator hay leader election.
- Nhược: nhiều state hơn (3 cột thêm) và một tham số phải chọn đúng (thời hạn
  lease). Lease quá ngắn thì sinh duplicate không cần thiết; quá dài thì recovery
  sau crash chậm.
- Rủi ro: lease ngắn hơn `delivery.timeout.ms` là một lỗi thiết kế im lặng — instance
  thứ hai reclaim trong khi instance thứ nhất vẫn đang gửi. Phải rào bằng cách chọn
  số, không phải bằng lời khuyên.

### Option C — Không có `PROCESSING`, chỉ `PENDING` → `PUBLISHED`, một instance duy nhất

Dùng advisory lock để chỉ một instance chạy publisher.

- Ưu: không có claim, không có lease, không có stale recovery.
- Nhược: publisher thành single point of failure; instance giữ advisory lock mà bị
  network partition sẽ chặn mọi instance khác; và mất hoàn toàn khả năng quan sát
  row nào đang in-flight.
- Rủi ro: đánh đổi một vấn đề dễ thấy (claim protocol) lấy một vấn đề khó thấy
  (leader election tự viết).

## Decision

Dùng **Option B — lease claim** cho mọi outbox publisher trong PayFlow.

### Cột thêm vào `outbox_events`

Ba cột này nằm ngay trong migration tạo bảng, không thêm sau:

| Cột | Kiểu | Ý nghĩa |
| --- | --- | --- |
| `lock_owner` | `VARCHAR(100) NULL` | Instance đang giữ claim. `NULL` khi không ai giữ |
| `lock_until` | `TIMESTAMPTZ NULL` | Thời điểm claim hết hiệu lực. Sau mốc này, instance khác được phép reclaim |
| `last_error` | `VARCHAR(500) NULL` | Nguyên nhân lần thất bại gần nhất, đã truncate |

`lock_owner` là `<spring.application.name>:<UUID sinh lúc JVM start>`. Không dùng
hostname: hai container có thể trùng hostname pattern, và một instance restart phải
được coi là một owner mới. Việc reclaim **không** so sánh owner cũ — nó chỉ nhìn
`lock_until`, nên identity chỉ dùng để chẩn đoán và để mark có điều kiện.

`last_error` chỉ chứa tên exception class và message đã truncate. Không chứa payload,
không chứa stack trace, không chứa gì có thể là secret — cùng lý do như quy tắc
redaction ở [ADR-004](ADR-004-transactional-outbox-polling-publisher.md).

### Giao thức, ba bước

**Bước 1 — claim (một transaction ngắn, commit trước khi có bất kỳ I/O Kafka nào)**

Reclaim row quá hạn trước, rồi claim row `PENDING` tới hạn cho phần budget còn lại.
Hai câu lệnh riêng trong cùng transaction, không phải một câu `OR`, để mỗi câu dùng
được index riêng của nó:

```sql
-- 1a. Reclaim: claim đã hết hiệu lực. Bình thường trả 0 row.
UPDATE payment.outbox_events
   SET lock_owner = :owner,
       lock_until = now() + :lease,
       attempt_count = attempt_count + 1
 WHERE id IN (
       SELECT id FROM payment.outbox_events
        WHERE status = 'PROCESSING' AND lock_until < now()
        ORDER BY created_at
        LIMIT :budget
        FOR UPDATE SKIP LOCKED)
RETURNING *;

-- 1b. Claim mới.
UPDATE payment.outbox_events
   SET status = 'PROCESSING',
       lock_owner = :owner,
       lock_until = now() + :lease,
       attempt_count = attempt_count + 1
 WHERE id IN (
       SELECT id FROM payment.outbox_events
        WHERE status = 'PENDING' AND next_attempt_at <= now()
        ORDER BY created_at
        LIMIT :remaining
        FOR UPDATE SKIP LOCKED)
RETURNING *;
```

`FOR UPDATE SKIP LOCKED` trong sub-select xử lý phần đua giữa các instance: instance
thứ hai bỏ qua row đang bị instance thứ nhất lock thay vì chờ. Transaction này chỉ
chạm database nên nó ngắn theo cấu trúc, không phải ngắn nhờ kỷ luật.

`attempt_count` tăng lúc **claim**, không phải lúc thất bại. Đây là lựa chọn có chủ
đích: một row làm process chết trước khi kịp báo lỗi vẫn phải tiến tới terminal
state, nếu không nó là một crash loop vĩnh viễn.

**Bước 2 — publish (không có transaction database nào mở)**

Gửi theo đúng thứ tự `created_at` đã claim, chờ ack của row trước khi gửi row sau.
Kafka key là `aggregateId`.

Nếu một row thất bại, mọi row còn lại **cùng `aggregate_id`** trong batch đó được
nhả về `PENDING` với cùng `next_attempt_at`, để thứ tự per-aggregate không bị đảo khi
retry. Row của aggregate khác vẫn tiếp tục.

> Ở Phase 1A mỗi payment sinh đúng một outbox row, nên quy tắc thứ tự này chưa được
> bài test nào chạm tới. Nó được đặc tả ngay bây giờ để Phase 1B không phải retrofit,
> và test cho nó bị hoãn tới khi payment có event thứ hai. Đây là đặc tả, không phải
> guarantee đã kiểm chứng.

Producer config bắt buộc: `enable.idempotence=true`, `acks=all`,
`delivery.timeout.ms=60000`. Idempotent producer chỉ chống duplicate **trong một lần
send** khi Kafka tự retry; nó không liên quan gì tới ranh giới
PostgreSQL ↔ Kafka và không tạo ra exactly-once (spec §8.8).

**Bước 3 — mark (một transaction ngắn, có điều kiện)**

```sql
UPDATE payment.outbox_events
   SET status = 'PUBLISHED', published_at = now(),
       lock_owner = NULL, lock_until = NULL, last_error = NULL
 WHERE id = :id AND status = 'PROCESSING' AND lock_owner = :owner;
```

Điều kiện `lock_owner = :owner` là bắt buộc. Nếu trả 0 row thì lease đã hết hạn và
instance khác đang giữ row — phải log warning kèm `id` và **không** ghi đè. Ghi đè
trong tình huống này là mark `PUBLISHED` một row mà mình không còn quyền, che mất một
lần publish trùng.

Khi thất bại và chưa đạt `max-attempts`:

```sql
UPDATE payment.outbox_events
   SET status = 'PENDING', next_attempt_at = :backoff,
       lock_owner = NULL, lock_until = NULL, last_error = :error
 WHERE id = :id AND status = 'PROCESSING' AND lock_owner = :owner;
```

Khi `attempt_count >= max-attempts`: `status = 'FAILED'`, nhả lock, giữ `last_error`.

### Tham số và lý do chọn

Cấu hình qua `payflow.outbox.*`, giá trị mặc định:

| Tham số | Mặc định | Lý do |
| --- | --- | --- |
| `poll-interval` | 500ms (fixed delay) | Sàn latency chấp nhận được cho một API trả `202`; đủ thưa để không đốt connection khi bảng rỗng |
| `batch-size` | 100 | Giới hạn lượng row bị giữ claim nếu instance chết ngay sau bước 1 |
| `lease` | 120s | **Phải lớn hơn `delivery.timeout.ms` (60s) một khoảng an toàn rõ rệt.** Reclaim chỉ được xảy ra khi lần gửi trước đã chắc chắn bỏ cuộc; reclaim sớm hơn là tự sinh duplicate |
| `max-attempts` | 10 | Với backoff dưới đây, row poison tới terminal state sau khoảng 20 phút thay vì quay vòng mãi |
| backoff | `next_attempt_at = now() + min(2^attempt_count, 300) giây` | Attempt 1 retry sau 2s (đủ cho lỗi thoáng qua), cap 300s để một Kafka down nửa tiếng không đẩy retry ra hàng giờ |

Các số này là **giá trị cấu hình có lập luận, chưa được benchmark**. Không có phép đo
throughput hay latency nào tồn tại cho publisher này. Bất kỳ con số hiệu năng nào
xuất hiện sau này phải kèm lệnh tái tạo.

### Cửa sổ crash, đầy đủ

| Crash ở đâu | State trong DB | Event có ở Kafka? | Recovery | Kết quả quan sát được |
| --- | --- | --- | --- | --- |
| Trước khi commit bước 1 | `PENDING` | Không | Lần poll sau nhặt lại | Không ảnh hưởng gì |
| Sau bước 1, trước khi gửi | `PROCESSING`, `lock_until` còn hiệu lực | Không | Reclaim khi `lock_until` hết hạn | Publish chậm tối đa bằng lease |
| Sau khi gửi, trước bước 3 | `PROCESSING` | **Có** | Reclaim rồi gửi lại | **Duplicate.** Consumer inbox loại bỏ. Đây là chỗ at-least-once thể hiện |
| Sau khi commit bước 3 | `PUBLISHED` | Có | Không cần gì | Đường đi mong muốn |
| Send trả lỗi timeout | `PENDING` + backoff | **Không biết được** | Retry | Có thể duplicate. Một send timeout không chứng minh broker đã từ chối |

Hai dòng cuối là lý do vì sao dự án này **không** được phép tuyên bố exactly-once ở
bất kỳ tài liệu nào. Duplicate không phải trường hợp hiếm cần xử lý sau; nó là hệ quả
cấu trúc của việc PostgreSQL và Kafka không có transaction chung.

### `FAILED` và recovery vận hành

Ở Phase 1A `FAILED` là **terminal**, vì DLT thuộc Phase 2. Row `FAILED` là một event
nghiệp vụ đã commit nhưng không tới được Kafka: đó là một sự cố cần người xử lý, và
phải có alert, không phải chỉ có một hàng trong bảng.

Requeue là thao tác thủ công, sau khi đã sửa nguyên nhân gốc, và chỉ reset các cột
điều phối:

```sql
UPDATE payment.outbox_events
   SET status = 'PENDING', attempt_count = 0, next_attempt_at = now(),
       lock_owner = NULL, lock_until = NULL, last_error = NULL
 WHERE id = :id AND status = 'FAILED';
```

Không bao giờ sửa `payload`, `event_type`, `aggregate_id` hay `created_at` của một
row đã tồn tại. Muốn phát nội dung khác thì đó là một event mới, có `eventId` mới.
Lệnh trên chạm dữ liệu liên quan tới tiền nên nó là quyết định của con người, không
phải của một job tự động.

### Điều không được suy diễn từ ADR này

- Không quyết định inbox/consumer dedup semantics — OD-007 vẫn `OPEN`, và ADR này
  chỉ *giả định* consumer sẽ idempotent.
- Không đưa DLT vào Phase 1A.
- Không quyết định retention/archival cho row `PUBLISHED`.
- Không phê duyệt event schema nào, cũng không phê duyệt bảng nghiệp vụ nào.
- Không nới bất kỳ invariant nào trong `.agent/AGENTS.md`.

## Consequences

### Positive

- Không có row nào mắc kẹt vĩnh viễn: mọi `PROCESSING` đều có deadline.
- Multi-instance an toàn mà không cần leader election, coordinator hay Redis lock.
- Không có transaction nào tồn tại qua network I/O, nên Kafka chậm biểu hiện thành
  Kafka chậm chứ không thành sự cố database.
- `SELECT status, count(*) FROM payment.outbox_events GROUP BY status` là một câu
  chẩn đoán đầy đủ, đọc được bằng mắt.
- `reclaimed` là một tín hiệu vận hành thật: bình thường nó bằng 0.

### Negative/trade-offs

- Thêm 3 cột và một giao thức 3 bước; phức tạp hơn Option A đáng kể.
- Lease 120s nghĩa là recovery sau `kill -9` chậm tối đa 2 phút. Đây là đánh đổi có ý
  thức: chậm hơn nhưng ít duplicate hơn.
- Ba round-trip database cho mỗi batch thay vì một.
- Gửi tuần tự trong batch giới hạn throughput theo latency round-trip tới broker.
  Chấp nhận ở Phase 1A; nếu cần nới thì phải có phép đo trước, và giữ nguyên tính
  tuần tự trong cùng `aggregate_id`.
- `attempt_count` tăng lúc claim nghĩa là một lần reclaim do crash cũng ăn một
  attempt. Một service crash-loop có thể đẩy row tới `FAILED` dù bản thân row không
  có vấn đề gì. Đánh đổi này chọn "dừng lại và báo động" thay vì "quay vòng im lặng".

## Contract and data impact

- API: không. Không endpoint nào expose trạng thái outbox ở Phase 1A.
- Event: không đổi envelope. `eventId` được sinh lúc **insert** outbox, không phải
  lúc publish, để retry gửi lại đúng `eventId` cũ — nếu không thì inbox của consumer
  không thể dedupe và toàn bộ chuỗi at-least-once vô nghĩa. Đây là ràng buộc quan
  trọng nhất mà ADR này áp lên ADR-004.
- Database/migration: `outbox_events` tạo kèm 3 cột lease trong cùng migration V2.
  Index:

  | Index | Mục đích |
  | --- | --- |
  | `(next_attempt_at, created_at) WHERE status = 'PENDING'` | Truy vấn claim ở bước 1b |
  | `(lock_until) WHERE status = 'PROCESSING'` | Truy vấn reclaim ở bước 1a |
  | `(aggregate_type, aggregate_id, created_at)` | Chẩn đoán và timeline |

  Spec §15.4 ghi index outbox là `status, next_attempt_at, created_at`. ADR này dùng
  **partial index** thay vì composite thường: row `PUBLISHED` sẽ chiếm gần như toàn
  bộ bảng và không bao giờ xuất hiện trong hai truy vấn nóng, nên đưa chúng vào index
  chỉ làm index phình ra. Đây là sai lệch có chủ đích so với spec, ghi lại tại đây.
- Security/privacy: `lock_owner` chứa UUID sinh tại runtime, không chứa hostname hay
  thông tin hạ tầng. `last_error` bị truncate ở 500 ký tự và không được ghi payload —
  đồng thời đây là dữ liệu nội bộ, tuyệt đối không xuất hiện trong response API
  (`.agent/AGENTS.md` §8: không expose internal detail).
- Observability/operations: metric bắt buộc, tên theo Micrometer:

  | Metric | Loại | Vì sao cần |
  | --- | --- | --- |
  | `payflow.outbox.pending.age.seconds` | gauge | Tuổi row `PENDING` tới hạn cũ nhất. Alert > 60s theo spec |
  | `payflow.outbox.published` | counter | Throughput |
  | `payflow.outbox.publish.failed` | counter | Tỉ lệ lỗi |
  | `payflow.outbox.reclaimed` | counter | Khác 0 nghĩa là có crash hoặc lease quá ngắn |
  | `payflow.outbox.failed.terminal` | counter | Alert ở bất kỳ giá trị > 0 |

  Log lúc publish phải mang `correlationId` của event, không phải một correlation id
  mới của scheduler.

## Rollout and rollback

Ba cột lease có ngay trong migration đầu tiên tạo bảng, nên không có bước migrate dữ
liệu và không có cửa sổ tương thích. Publisher và bảng vào cùng một release.

Shutdown phải graceful: dừng nhận batch mới, để batch đang chạy kết thúc. Kill giữa
batch thì rơi vào đúng các cửa sổ crash đã liệt kê ở trên — không có mất mát, chỉ có
độ trễ tối đa bằng lease và khả năng duplicate.

Rollback = tắt scheduler bằng config. Row nằm lại `PENDING`, không mất gì. Không có
đường rollback nào cần `DELETE`.

Nếu sau này cần đổi tham số lease, ràng buộc `lease > delivery.timeout.ms` phải được
kiểm tra lúc startup và fail-fast, chứ không để trong tài liệu — đây là một mục
implementation bắt buộc của Phase 1A, không phải khuyến nghị.

## Verification

Chưa có test nào chạy. Publisher chưa được implement. Đây là danh sách bắt buộc phải
xanh trước khi Phase 1A được coi là xong, tất cả dùng Testcontainers với PostgreSQL
và Kafka thật (không mock repository, không H2 — `.docs/TESTING_STRATEGY.md`):

1. **Claim loại trừ lẫn nhau:** hai publisher instance chạy song song trên cùng bảng
   với 200 row; mỗi row được publish đúng một lần; không instance nào chờ instance
   kia.
2. **Reclaim stale:** chèn tay một row `PROCESSING` với `lock_until` trong quá khứ và
   một owner khác; publisher phải nhặt lại và publish; counter `reclaimed` tăng 1.
3. **Không reclaim lease còn hiệu lực:** cùng setup nhưng `lock_until` ở tương lai;
   publisher phải bỏ qua.
4. **Mark có điều kiện:** giả lập lease hết hạn giữa bước 2 và bước 3 (đổi
   `lock_owner` trong DB trước khi mark); mark phải trả 0 row và log warning, row
   không bị chuyển `PUBLISHED` bởi owner cũ.
5. **Backoff:** buộc send fail; xác nhận `attempt_count` tăng, `status` về `PENDING`,
   `next_attempt_at` đúng công thức, `last_error` có nội dung và không chứa payload.
6. **Terminal:** fail liên tục tới `max-attempts`; row thành `FAILED`, không được
   claim lại, counter `failed.terminal` tăng.
7. **Kafka down rồi lên:** dừng container Kafka, tạo payment, xác nhận `202` và row
   `PENDING`; start lại Kafka, xác nhận row chuyển `PUBLISHED`.
8. **Duplicate là đúng, không phải flake:** giả lập crash sau send trước mark; xác
   nhận Kafka nhận hai bản có **cùng `eventId`** — đây là bằng chứng inbox có thể
   dedupe được.
9. **Fail-fast cấu hình:** `lease <= delivery.timeout.ms` phải làm context refresh
   thất bại.

Kiểm tra thường trực ở review: không có `@Transactional` nào bao quanh một lời gọi
`KafkaTemplate`, và không có `SELECT ... FOR UPDATE` nào trong publisher ngoài hai
câu claim ở bước 1.
