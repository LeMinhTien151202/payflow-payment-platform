# Architecture Decision Records

ADR ghi quyết định có trade-off dài hạn, không ghi lại mọi chi tiết implementation.

## Trạng thái

```text
PROPOSED -> ACCEPTED -> SUPERSEDED
                 \-> DEPRECATED
```

Chỉ ADR `ACCEPTED` mới được dùng để tinh chỉnh quyết định trong spec. ADR không được âm thầm nới invariant tài chính hoặc quy tắc an toàn.

ADR chính thức (đã viết) nằm ở [`docs/adr/`](../../docs/adr/README.md), không nằm trong `.docs/`. File này chỉ giữ backlog quyết định và quy tắc tạo ADR.

## Backlog ban đầu

| ID | Quyết định | Trạng thái |
| --- | --- | --- |
| ADR-001 | Microservices theo phase thay vì dựng full topology ngay | PROPOSED |
| ADR-002 | Kafka cho asynchronous workflow | PROPOSED |
| ADR-003 | Payment Service làm Saga orchestrator | PROPOSED |
| ADR-004 | Polling Transactional Outbox trước Debezium | ACCEPTED — [docs/adr/ADR-004](../../docs/adr/ADR-004-transactional-outbox-polling-publisher.md) |
| ADR-005 | Database-per-service và local schema isolation | PROPOSED |
| ADR-006 | Keycloak cho OIDC/OAuth2 | PROPOSED |
| ADR-007 | PostgreSQL + BigDecimal/NUMERIC cho money | ACCEPTED — [docs/adr/ADR-007](../../docs/adr/ADR-007-postgresql-money-representation.md) |
| ADR-008 | Spring Cloud Gateway ở edge | PROPOSED |
| ADR-009 | Monorepo + shared-contract giới hạn | PROPOSED |
| ADR-010 | OpenTelemetry cho distributed tracing | PROPOSED |
| ADR-011 | Điểm commit thành công giữa ledger post, payment success và account capture | PROPOSED |
| ADR-012 | Đưa failure-recovery hardening thành gate trước service split | PROPOSED |
| ADR-013 | Platform version baseline: Spring Boot 4.0.7 + Spring Cloud 2025.1.2 | ACCEPTED — [docs/adr/ADR-013](../../docs/adr/ADR-013-platform-version-baseline.md) |
| ADR-014 | Outbox claim lease, stale recovery và crash semantics | ACCEPTED — [docs/adr/ADR-014](../../docs/adr/ADR-014-outbox-claim-lease-and-recovery.md) |

ADR-013 không có trong backlog gốc: nó phát sinh khi Phase 0 phát hiện spec §3.1 khai báo một cặp version không tồn tại (Spring Boot 4.1.x + Spring Cloud 2025.1.x). Quyết định được ghi lại thay vì âm thầm chọn một phía.

ADR-014 cũng không có trong backlog gốc. ADR-004 chỉ chọn *cơ chế* publish; phần OD-008 yêu cầu (claim lease, stale recovery, crash window, backoff, terminal state, metric) là một quyết định riêng với trade-off riêng, nên tách thành ADR-014 thay vì nhồi vào ADR-004. Đây là hai ADR giải chung một blocker: OD-008 chỉ `RESOLVED` khi có cả hai.

## Quy tắc tạo ADR

- Copy `ADR-TEMPLATE.md` thành `ADR-NNN-short-title.md`.
- Một ADR trả lời một quyết định chính.
- Ghi alternatives thực sự đã cân nhắc và hậu quả tiêu cực, không chỉ biện minh lựa chọn.
- Ghi migration/rollback và tác động API/event/data/operations nếu có.
- Khi thay quyết định, tạo ADR mới và đánh dấu ADR cũ `SUPERSEDED`; không sửa lịch sử như chưa từng tồn tại.
- Cập nhật bảng backlog/index trong file này.
