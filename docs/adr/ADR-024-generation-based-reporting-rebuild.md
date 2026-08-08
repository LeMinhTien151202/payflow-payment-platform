# ADR-024: Reporting rebuild bằng projection generation

- Status: ACCEPTED
- Date: 2026-08-07

## Context

Update trực tiếp read model đang phục vụ traffic làm dashboard thấy trạng thái nửa rebuild. Xóa rồi
replay cũng không có điểm so sánh để phát hiện projector mới tạo kết quả khác projector cũ.

## Decision

Reporting lưu envelope vào `event_log` theo `event_id`, project theo `generation_id`, và query chỉ đọc
generation active. Rebuild tạo generation `BUILDING`, replay theo `(occurred_at,event_id)`, so
fingerprint với active rồi mới switch con trỏ trong transaction. Mismatch thành `REJECTED`.

Consumer chỉ nhận event version được hỗ trợ; version lạ đi qua bounded retry tới DLT. Rebuild cần
`reporting:rebuild` và audit append-only; merchant report cần `reporting:read` và `merchant_id` từ JWT.

## Consequences

Cần dung lượng cho event log/generation cũ, đổi lại rebuild không tạo khoảng trống dashboard, có rollback
tự nhiên bằng active pointer và có equivalence gate kiểm thử được.
