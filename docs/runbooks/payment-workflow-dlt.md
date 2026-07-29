# Runbook: Payment workflow Dead Letter Topic

## Phạm vi

Payment Service consume các topic Risk, Account và Ledger bằng group
`payment-saga-orchestrator-v1`. Listener chỉ acknowledge sau khi transaction local hoàn tất. Lỗi được
retry hữu hạn; sau đó original key/value được chuyển tới `payflow.dead-letter.v1`. Runtime này đã có
code nhưng chưa được kiểm chứng với Kafka thật.

## Triage

1. Dừng replay tự động và ghi lại original topic/partition/offset, Kafka key, `eventId`, `eventType`,
   `aggregateId`, correlation/causation ID và exception headers.
2. Xác nhận key bằng `aggregateId`/`paymentId`. Không sửa key để ép record qua consumer.
3. Kiểm tra `payment.processed_events`. Có marker nghĩa là business transaction trước đã commit;
   replay cùng `eventId` phải trở thành duplicate no-op.
4. Kiểm tra Payment, Saga và outbox trong cùng database owner. Không kết luận từ Kafka offset riêng lẻ.
5. Phân loại nguyên nhân: payload/contract vĩnh viễn, state/fact mâu thuẫn, hay dependency/transient đã
   được khắc phục.

Không log hoặc đưa raw payload vào ticket nếu contract tương lai có dữ liệu nhạy cảm.

## Replay an toàn

- Chỉ replay sau khi root cause đã được sửa và trạng thái Payment/Saga được đối chiếu.
- Giữ nguyên original payload, Kafka key và đặc biệt là `eventId`; tạo event ID mới sẽ né inbox và có
  thể lặp side effect.
- Replay về original topic, không consume DLT bằng chính Payment workflow group.
- Theo dõi processed/duplicate/failed metric, outbox mới và Saga transition sau replay.
- Nếu facts mâu thuẫn hoặc journal đã POSTED nhưng capture chưa rõ, giữ manual review; không release.

Hiện chưa có operations replay endpoint. Cho tới khi endpoint có audit được triển khai và kiểm thử,
replay thủ công chỉ dành cho môi trường local/disposable và phải lưu command/output làm evidence.

## Điều kiện đóng incident

- Record đã được xử lý hoặc xác nhận duplicate bằng cùng `eventId`.
- Payment/Saga/outbox nhất quán với ADR-011/012/018.
- DLT không tiếp tục tăng vì cùng nguyên nhân.
- Root cause, actor, thời điểm và kết quả replay được lưu; không có secret trong evidence.
