# ADR-020: Giữ refundable capacity bằng khóa hàng Payment

- Status: ACCEPTED
- Date: 2026-07-29
- Decision owners: PayFlow repository owner
- Supersedes: N/A
- Superseded by: N/A
- Resolves: OD-005

## Context

Nếu hai refund cùng đọc `amount - total_refunded_amount` rồi cùng tạo side effect, tổng refund có thể vượt
payment gốc. Một biến `total_refunded_amount` cũng không đủ nếu nó chỉ tăng sau thành công: refund đang
`CREATED`/`PROCESSING` vẫn phải giữ chỗ để request khác không tiêu cùng capacity.

## Decision drivers

- Tổng succeeded và in-flight refund không vượt gross payment amount.
- Trạng thái API không được tuyên bố đã refund trước khi financial outcome thành công.
- Idempotency record, capacity, refund và outbox phải commit hoặc rollback cùng nhau.
- Transaction giữ lock phải ngắn và không chứa network/Kafka I/O.
- Failure phải trả capacity đúng một lần; duplicate event phải là no-op.

## Options considered

### Option A — Check rồi insert không lock

Đơn giản nhưng có write-skew; database constraint trên từng refund row không nhìn được tổng các row. Bị
loại.

### Option B — Atomic native UPDATE trên Payment

Có thể giữ capacity nhưng dễ làm JPA aggregate/version bị lệch khi còn phải ghi refund, status history và
outbox trong cùng use case.

### Option C — `SELECT ... FOR UPDATE` Payment rồi cập nhật trong local transaction

Tuần tự hóa refund intake theo một payment, giữ được một aggregate/version và cho thông báo từ chối rõ
ràng. Lock chỉ tồn tại trong local database transaction.

## Decision

Chọn Option C. Payment sở hữu hai số tiền:

- `total_refunded_amount`: chỉ tổng refund `SUCCEEDED`;
- `reserved_refund_amount`: tổng refund `CREATED` hoặc `PROCESSING` đang giữ capacity.

Refund intake mở local transaction, khóa đúng payment bằng merchant scope, kiểm tra status/currency và:

```text
available = payment.amount - total_refunded_amount - reserved_refund_amount
requested <= available
```

Sau đó tăng `reserved_refund_amount`, tạo refund `CREATED`, lưu idempotent response và append outbox trong
cùng transaction. Không gọi service khác và không publish Kafka khi đang giữ lock.

Khi refund chuyển `CREATED -> PROCESSING`, capacity không đổi. Khi `SUCCEEDED`, cùng transaction giảm
reserved và tăng total refunded; khi `FAILED`, giảm reserved. Duplicate outcome được chặn bởi inbox và
state machine. Payment chỉ chuyển `PARTIALLY_REFUNDED`/`REFUNDED` sau outcome `SUCCEEDED`; refund đang
giữ chỗ không thay đổi trạng thái payment hiển thị cho client.

Database luôn kiểm tra:

```text
total_refunded_amount >= 0
reserved_refund_amount >= 0
total_refunded_amount + reserved_refund_amount <= amount
```

Refund không được nhận khi payment chưa `SUCCEEDED` hoặc `PARTIALLY_REFUNDED`.

## Consequences

### Positive

- Không oversubscribe capacity kể cả request đồng thời.
- Failure release và success consume có nghĩa rõ ràng, kiểm toán được.
- JPA optimistic version, payment status/history và outbox không bị native update đi vòng aggregate.

### Negative/trade-offs

- Refund cùng một payment được tuần tự hóa; đây là contention có chủ ý tại đúng financial boundary.
- Cần timeout/monitor cho transaction để tránh giữ lock lâu.
- PostgreSQL concurrency semantics chỉ được coi verified sau khi chạy Testcontainers.

## Contract and data impact

- API: `POST /api/v1/payments/{paymentId}/refunds` bắt buộc `Idempotency-Key`, trả `202` khi đã giữ capacity.
- Event: refund requested/outcome dùng refundId làm identity và paymentId làm Kafka key để giữ ordering.
- Database/migration: thêm hai amount vào payment và bảng refund; CHECK constraint là guard cuối.
- Security/privacy: merchant chỉ khóa/refund payment thuộc merchant lấy từ token; không tin merchantId từ body.
- Observability/operations: counter capacity rejection, refund in-flight gauge và lock/transaction latency;
  không dùng UUID làm metric label.

## Rollout and rollback

Thêm cột zero/backfill và constraint trước khi nhận refund. Tắt endpoint/consumer để rollback hành vi; không
xóa refund hoặc giảm các tổng đã commit. Recovery/reconciliation xử lý refund in-flight quá SLA.

## Verification

- Pure domain test reserve, success, failure, partial/full và illegal status/currency.
- Idempotency test cùng key/cùng body replay, cùng key/khác body conflict.
- PostgreSQL Testcontainers test hai transaction đồng thời chỉ một request lấy phần capacity cuối.
- Atomic rollback test không để capacity, refund, idempotency hoặc outbox mồ côi.
- Kafka duplicate/out-of-order outcome test trước khi coi runtime hoàn chỉnh.
