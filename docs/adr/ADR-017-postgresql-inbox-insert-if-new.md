# ADR-017: PostgreSQL inbox dùng insert-if-new trong local transaction

- Status: ACCEPTED
- Date: 2026-07-28
- Decision owners: PayFlow repository owner
- Supersedes: N/A
- Superseded by: N/A

## Context

PayFlow giao event theo at-least-once nên consumer chắc chắn có thể nhận lại cùng `eventId`.
Một `INSERT` thường rồi bắt unique-violation không an toàn trên PostgreSQL: lỗi unique đánh dấu
toàn bộ transaction là aborted, vì vậy business change không thể tiếp tục trong transaction đó.
Consumer cũng không được ghi inbox và business data trong hai transaction khác nhau vì crash ở giữa
sẽ tạo cửa sổ mất xử lý hoặc xử lý lặp.

## Decision drivers

- Mỗi `(event_id, consumer_name)` chỉ được phép áp dụng business effect một lần.
- Inbox insert, business mutation và outbox outcome phải commit/rollback cùng nhau.
- Duplicate là đường đi bình thường, không phải exception hay DLT.
- Kafka offset chỉ được acknowledge sau khi local transaction thành công.

## Options considered

### Option A — `INSERT ... ON CONFLICT DO NOTHING`

Affected row bằng `1` cấp quyền chạy business logic; bằng `0` là duplicate và kết thúc thành công.
Transaction không bị aborted. Đây là SQL PostgreSQL rõ ràng, ngắn và kiểm thử được.

### Option B — `INSERT` rồi bắt unique violation bằng savepoint

Có thể đúng nếu quản lý savepoint chính xác, nhưng phức tạp hơn và dễ vô tình catch exception trong
một transaction đã hỏng. Không có lợi ích tương xứng cho PayFlow.

### Option C — kiểm tra tồn tại rồi insert

Bị race giữa `SELECT` và `INSERT`; hai consumer đồng thời vẫn có thể cùng chạy business logic.
Phương án này bị loại.

## Decision

Chọn Option A. Mỗi service có bảng `processed_events` trong schema của chính nó với primary key
`(event_id, consumer_name)`. Consumer application service mở một local transaction và thực hiện:

1. insert inbox bằng `ON CONFLICT (event_id, consumer_name) DO NOTHING`;
2. nếu affected row là `0`, trả thành công mà không chạy domain/business/outbox;
3. nếu là `1`, áp dụng domain change và append outcome vào outbox;
4. chỉ sau khi transaction commit mới cho phép Kafka container commit offset.

Inbox adapter dùng transaction propagation `MANDATORY` để fail-fast nếu bị gọi ngoài transaction.
Không lưu payload hoặc header trong inbox; chỉ lưu identity/tên event/aggregate/timestamp cần cho
dedupe và vận hành. `eventId` đến từ envelope gốc, không được sinh lại khi retry.

## Consequences

### Positive

- Duplicate không làm transaction PostgreSQL hỏng và không tạo side effect lần hai.
- Một unique key bảo vệ đồng thời nhiều instance consumer.
- Bảng nhỏ, không nhân bản payload có thể chứa dữ liệu nhạy cảm.

### Negative/trade-offs

- SQL phụ thuộc PostgreSQL; đây là chủ đích và phải test bằng PostgreSQL thật, không dùng H2.
- Cần retention dài hơn thời gian Kafka/event có thể được replay; xóa quá sớm sẽ làm mất dedupe.
- Adapter không tự tạo transaction, vì transaction phải bao phủ cả business change và outbox.

## Contract and data impact

- API: không đổi.
- Event: không đổi; mọi producer phải giữ nguyên `eventId` khi retry/republish.
- Database: migration forward-only tạo `payment.processed_events`; service khác tạo bảng tương đương
  trong schema riêng khi trở thành deployable.
- Security/privacy: không lưu payload, token, header hoặc secret.
- Observability: consumer phải đếm duplicate theo `consumer_name` và log `eventId`, `aggregateId`,
  `correlationId` ở mức phù hợp; không log payload.

## Rollout and rollback

Chạy migration trước hoặc cùng release consumer. Chưa bật listener cho tới khi migration thành công.
Rollback code bằng cách tắt listener; không drop bảng vì lịch sử dedupe phải được giữ lại. Mọi thay đổi
retention sau này cần runbook và phải lớn hơn replay horizon đã công bố.

## Verification

- Unit test xác nhận affected row `1/0` ánh xạ thành new/duplicate và SQL có `ON CONFLICT DO NOTHING`.
- PostgreSQL Testcontainers test xác nhận duplicate trong cùng transaction không abort transaction.
- Concurrency test hai transaction cùng `eventId`/consumer chỉ có một winner.
- Integration test của từng consumer xác nhận inbox + business + outbox rollback cùng nhau khi lỗi.
- Kafka test xác nhận offset không commit khi local transaction rollback và redelivery trở thành
  duplicate no-op sau một lần commit thành công.

