# Runbook: Payment Saga cần manual review

## Mục đích

Runbook này áp dụng khi Payment/Saga ở `MANUAL_REVIEW_REQUIRED` hoặc alert báo Saga compensation quá
hạn. Contract nguồn là ADR-011, ADR-012 và ADR-018.

> Trạng thái hiện tại: core policy, event contract và migration đã có; scheduler, operations endpoint,
> Kafka wiring và dashboard chưa được bật. Không thao tác trực tiếp database để giả lập resolution.

## Triage an toàn

1. Ghi lại `paymentId`, correlation/trace ID, `sagaStep`, `reasonCode`, retry count và deadline.
2. Đọc Payment và Saga state từ owner của chúng; xác định rõ `reservationId` và `journalId` có tồn tại
   hay không.
3. Kiểm tra inbox/outbox theo `eventId` và causation chain trước khi kết luận message bị mất.
4. Hỏi đúng owner về fact đã commit: Account cho reservation/capture/release, Ledger cho journal.
5. Chỉ chọn action mà recovery policy cho phép; giữ bằng chứng và audit actor/reason.

## Decision table

| Facts đã xác minh | Action hợp lệ |
| --- | --- |
| Risk cần review, chưa reserve | reject hoặc resume risk workflow theo quyết định được audit |
| Reservation có, journal không có | retry Ledger hữu hạn hoặc phát release compensation đúng intent |
| Journal `POSTED`, capture chưa xác nhận | retry/query capture, rồi reconciliation; **không release** |
| Release đã commit | consume đúng release fact, hoàn tất compensation và chuyển Payment `FAILED` |
| Facts mâu thuẫn/không xác định | giữ manual review, escalate; không đoán outcome |

## Điều cấm

- Không sửa trực tiếp Payment/Saga/reservation status trong PostgreSQL.
- Không republish với `eventId` mới để né inbox deduplication.
- Không release sau khi journal đã `POSTED`.
- Không đánh dấu `SUCCEEDED` nếu journal và capture facts chưa cùng tồn tại.
- Không ghi token, secret, raw payload nhạy cảm vào ticket/log/audit.

## Điều kiện đóng incident

- Payment, Saga, reservation và journal có outcome nhất quán.
- Outbox/inbox chain không còn work item quá hạn cho payment đó.
- Action, actor, reason và evidence được audit.
- Nếu cần sửa code, có regression test tái hiện đúng failure window trước khi đóng.

