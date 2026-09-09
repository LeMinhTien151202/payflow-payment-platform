# ADR-025: Settlement và reconciliation dùng immutable event facts

- Status: ACCEPTED
- Date: 2026-08-08
- Decision owners: PayFlow repository owner
- Supersedes: N/A
- Superseded by: N/A

## Context

Phase 3 cần tính số tiền merchant được nhận và phát hiện sai lệch giữa Payment, Account, Ledger,
Refund và Settlement. Settlement không được đọc database của service khác hoặc tính lại fee bằng cấu
hình Merchant hiện tại. Event `payment.succeeded` v1 không có fee snapshot nên không đủ dữ liệu để
tính net amount theo ADR-019.

Kafka chỉ đảm bảo thứ tự trong một topic-partition. Payment, Refund, Account và Ledger dùng các topic
khác nhau, vì vậy reconciliation không được giả định các fact đến theo thứ tự toàn cục. Batch đã
`COMPLETED` cũng không được sửa âm thầm khi event cũ đến muộn.

## Decision

1. Payment phát `payment.succeeded` v2 với immutable `feePolicyVersion`, `appliedFeeRate`,
   `feeAmount`, `feeCurrency` và `feeRoundingMode`. Contract v1 được giữ để replay lịch sử; consumer
   Notification/Reporting hỗ trợ cả v1 và v2. Settlement chỉ nhận v2 vì v1 không đủ economics.
2. Settlement dùng business date từ `occurredAt` theo timezone cấu hình, mặc định
   `Asia/Ho_Chi_Minh`. Batch duy nhất theo `(merchant_id, settlement_date, currency)`.
3. Payment item đóng góp `gross=amount`, `refund=0`, `fee=feeAmount`. Refund item đóng góp
   `gross=0`, `refund=amount`, `fee=-feeReversalAmount`. Vì vậy công thức luôn là:

   ```text
   net = gross - refund - fee
   ```

   `fee` và `net` của batch được phép âm trong ngày có refund của giao dịch cũ; không được clamp hoặc
   đổi dấu để tạo báo cáo đẹp.
4. Consumer ghi inbox, typed financial fact, settlement item và batch delta trong một local
   PostgreSQL transaction. Unique business key `(reference_type, reference_id)` ngăn cùng payment hoặc
   refund được tính hai lần. Kafka chỉ được acknowledge sau commit.
5. Batch nhận item khi `OPEN`. Operations `run` khóa batch, tính lại totals từ item và chỉ chuyển
   `OPEN -> CALCULATING -> READY` khi stored totals khớp. Operations `complete` chỉ chuyển
   `READY -> COMPLETED`, ghi audit và outbox `settlement.completed` trong cùng transaction.
6. Event settlement đến sau khi batch không còn `OPEN` không sửa totals. Fact vẫn được giữ và một
   issue `LATE_SETTLEMENT_EVENT` được ghi để xử lý bằng adjustment policy có audit trong thay đổi sau.
7. Reconciliation lưu typed facts từ Payment/Refund/Account/Ledger, rồi so theo payment/refund ID:
   amount, currency, Ledger posted và Account captured/credited. Nó cũng so batch totals với tổng item.
   Mismatch được ghi/alert; không update Payment, balance hoặc journal. Issue chỉ được đánh dấu resolved
   khi lần chạy sau chứng minh facts đã khớp.
8. Settlement/reconciliation operations dùng scope riêng, typed allowlisted audit theo ADR-022 và
   không nhận arbitrary JSON/free-form note.

## State machine

```text
OPEN -> CALCULATING -> READY -> COMPLETED
          |             |
          +-----------> FAILED
FAILED -> CALCULATING
```

`COMPLETED` là terminal. Retry cùng operation trên `READY/COMPLETED` là idempotent nếu intent giống
nhau; không phát event/audit thứ hai.

## Consequences

- Settlement có thể rebuild và giải thích mọi line bằng event ID, fee snapshot và business reference.
- Việc hỗ trợ song song v1/v2 tăng code compatibility nhưng tránh reinterpret contract đã công bố.
- Batch âm là một fact hợp lệ, không phải lỗi kỹ thuật; payout mock/operations quyết định cách xử lý.
- Late event không mất nhưng cần adjustment workflow riêng trước production payout thật.

## Verification

- Contract JSON cho payment success v1/v2 và settlement completed v1.
- Unit test money scale/currency, payment/refund contribution và exact batch formula.
- PostgreSQL Testcontainers cho inbox/business duplicate, concurrent item append, unique batch,
  recalculation, append-only audit và completed-batch late event.
- Messaging/E2E gate sau khi bật Docker chứng minh redelivery, DLT, outbox crash window và full-profile
  settlement/reconciliation scenario.
