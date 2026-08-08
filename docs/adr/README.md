# Architecture Decision Records

Đây là các ADR chính thức của PayFlow. Backlog quyết định chưa viết nằm ở
[`.docs/adr/README.md`](../../.docs/adr/README.md); template ở
[`.docs/adr/ADR-TEMPLATE.md`](../../.docs/adr/ADR-TEMPLATE.md).

Chỉ ADR `ACCEPTED` được dùng để tinh chỉnh quyết định trong
`PAYFLOW_MICROSERVICE_PROJECT_SPEC.md`. ADR không được nới invariant tài chính hoặc
quy tắc an toàn trong [`.agent/AGENTS.md`](../../.agent/AGENTS.md).

| ID | Quyết định | Status | Date |
| --- | --- | --- | --- |
| [ADR-004](ADR-004-transactional-outbox-polling-publisher.md) | Transactional Outbox với polling publisher, Debezium để sau | ACCEPTED | 2026-07-26 |
| [ADR-007](ADR-007-postgresql-money-representation.md) | PostgreSQL với `NUMERIC(19,4)` và `BigDecimal` cho mọi giá trị tiền | ACCEPTED | 2026-07-26 |
| [ADR-011](ADR-011-ledger-capture-payment-success-ordering.md) | Ledger posted → explicit capture → payment success — resolves OD-001 | ACCEPTED | 2026-07-28 |
| [ADR-012](ADR-012-failure-recovery-before-service-split.md) | Failure recovery là gate bắt buộc trước Phase 2 — resolves OD-002 | ACCEPTED | 2026-07-28 |
| [ADR-013](ADR-013-platform-version-baseline.md) | Platform version baseline là Spring Boot 4.0.7 + Spring Cloud 2025.1.2 | ACCEPTED | 2026-07-26 |
| [ADR-014](ADR-014-outbox-claim-lease-and-recovery.md) | Outbox claim lease, stale recovery và crash semantics — resolves OD-008 | ACCEPTED | 2026-07-26 |
| [ADR-015](ADR-015-risk-score-saturation-and-level-bands.md) | Risk score v1 dùng saturation và level band cố định — resolves OD-009 | ACCEPTED | 2026-07-27 |
| [ADR-016](ADR-016-risk-assessment-event-taxonomy.md) | Một `risk.assessment.completed` hợp nhất — resolves OD-003 | ACCEPTED | 2026-07-28 |
| [ADR-017](ADR-017-postgresql-inbox-insert-if-new.md) | PostgreSQL inbox insert-if-new trong local transaction — resolves OD-007 | ACCEPTED | 2026-07-28 |
| [ADR-018](ADR-018-payment-manual-review-contract.md) | Payment `MANUAL_REVIEW_REQUIRED` và resolution theo Saga facts — resolves OD-006 | ACCEPTED | 2026-07-28 |
| [ADR-019](ADR-019-immutable-payment-fee-snapshot.md) | Payment đóng băng fee snapshot và refund reverse theo lũy kế — resolves OD-004 | ACCEPTED | 2026-07-29 |
| [ADR-020](ADR-020-pessimistic-refund-capacity-reservation.md) | Khóa hàng Payment để giữ refundable capacity — resolves OD-005 | ACCEPTED | 2026-07-29 |
| [ADR-021](ADR-021-refund-ledger-credit-finalization-ordering.md) | Ledger reversal → Account credit → refund success — resolves OD-011 | ACCEPTED | 2026-07-29 |

| [ADR-022](ADR-022-typed-allowlisted-audit-records.md) | Typed allowlisted append-only audit records | ACCEPTED | 2026-08-04 |
| [ADR-023](ADR-023-phase2-service-ownership-split.md) | Phase 2 Account/Ledger/Merchant ownership split | ACCEPTED | 2026-08-07 |
| [ADR-024](ADR-024-generation-based-reporting-rebuild.md) | Generation-based reporting rebuild | ACCEPTED | 2026-08-07 |

Các ADR còn lại trong khoảng ADR-001…ADR-012 vẫn ở trạng thái `PROPOSED` và chưa có
file: chúng thuộc phạm vi Phase 1B trở đi và sẽ được viết khi đúng lát cắt được triển
khai. Không implement scope của một ADR chưa `ACCEPTED` nếu
[`.docs/OPEN_DECISIONS.md`](../../.docs/OPEN_DECISIONS.md) còn đánh dấu `OPEN` cho
scope đó.

Một ADR `ACCEPTED` chỉ mở khoá **quyền implement**; nó không chứng nhận là đã
implement hay đã test. Trạng thái thực tế nằm ở
[`.docs/IMPLEMENTATION_STATUS.md`](../../.docs/IMPLEMENTATION_STATUS.md).
