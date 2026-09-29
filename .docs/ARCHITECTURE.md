# Kiến trúc PayFlow Core

## Driver

Thứ tự ưu tiên: đúng đắn của tiền → không mất/trùng side effect → recovery/audit → authorization →
quan sát được → mở rộng. Số lượng microservice không phải mục tiêu.

## Topology

```text
API Gateway
├── Payment Service ── Kafka ── Risk Service
│                 └── Kafka ── Account-Ledger Service
├── Notification Operations API
└── Settlement/Reconciliation API

payment outcomes ── Kafka ── Notification Service
payment/refund outcomes ── Kafka ── Settlement Service
```

Notification và Settlement là downstream fan-out; lỗi ở đó không rollback một payment đã hoàn thành.

## Hướng phụ thuộc trong service

```text
API/Kafka adapter -> application use case -> domain
infrastructure -> application port/domain
domain -> Java standard library
```

- Controller/listener chỉ parse, authorize và gọi use case.
- Application điều phối local transaction và port.
- Domain giữ invariant/state transition.
- Infrastructure triển khai PostgreSQL, Kafka, Redis, HTTP và security.

## Data ownership

Không cross-database join và không foreign key xuyên service. Mỗi service chỉ mutate database mình sở
hữu. Một PostgreSQL container local chứa nhiều database logic; đây vẫn là database-per-service ở mức
ownership/credential.

## Payment Saga

```text
create + outbox
 -> risk
 -> reserve
 -> post balanced journal
 -> capture
 -> payment.succeeded
 -> notification + settlement fan-out
```

Mỗi producer ghi Outbox cùng business transaction. Mỗi consumer ghi Inbox/`processed_events` cùng
side effect. Delivery at-least-once vì thế không biến thành business effect nhiều lần.

Nếu ledger thất bại sau reserve, Payment phát release compensation. Nếu trạng thái không chắc chắn sau
bounded retry, Saga vào manual review thay vì công bố outcome sai.

## Security

Keycloak phát JWT. Gateway kiểm issuer/signature và route scope. Business service kiểm lại JWT, scope,
actor type và merchant ownership. Internal event consumers không tin dữ liệu request ngoài event
contract và vẫn kiểm identity/correlation của envelope.

## Quyết định rút gọn

- Merchant policy là local data của Payment trong portfolio core.
- Account + Ledger là modular boundary trong một deployable.
- Reporting riêng bị bỏ; payment query và settlement đủ cho demo.
- Chỉ có Docker profile `core`; Kubernetes không nằm trong runtime hiện tại.

Tài liệu chi tiết cho người học: [PayFlow Core guide](../docs/architecture/payflow-core-guide.md).
