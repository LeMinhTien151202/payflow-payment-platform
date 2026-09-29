# PayFlow Core — mục lục tài liệu

Đọc theo thứ tự này để không bị lẫn với topology cũ:

1. [PayFlow Core guide](architecture/payflow-core-guide.md): chức năng, sáu service, luồng payment,
   Kafka, Outbox/Inbox, Saga, Keycloak, database và source path.
2. [Core local runbook](runbooks/core-local.md): build, start, token, smoke, log và webhook local.
3. [Payment OpenAPI](api/payment-service-v1.yaml): payment/refund/manual-review API.
4. [Notification Operations OpenAPI](api/notification-operations-v1.yaml): tra cứu/retry webhook.
5. [Settlement OpenAPI](api/settlement-service-v1.yaml): settlement/reconciliation API.
6. [Event contracts](events/payment-workflow-v1.md): contract workflow Kafka.
7. [ADR](adr/README.md): các quyết định reliability và money invariant.

Các runbook xử lý sự cố vẫn áp dụng:

- [Saga manual review](runbooks/saga-manual-review.md)
- [Outbox recovery](runbooks/outbox-recovery.md)
- [Payment workflow DLT](runbooks/payment-workflow-dlt.md)
- [Notification delivery failure](runbooks/notification-delivery-failure.md)
- [Settlement/reconciliation](runbooks/settlement-reconciliation.md)

[`archive/full-topology`](archive/full-topology/README.md) chỉ là lịch sử của bản nhiều service; không
dùng lệnh hoặc đường dẫn source trong đó cho nhánh `portfolio-core`.
