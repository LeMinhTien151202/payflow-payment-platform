# `ledger.payment-posted` v1

Quyết định ordering nằm trong [ADR-011](../adr/ADR-011-ledger-capture-payment-success-ordering.md).

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | Ledger boundary |
| Consumer chính | `payment-service` |
| Topic | `payflow.ledger.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `ledger.payment-posted` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, `journalId`, positive `amount` scale 4 và uppercase three-letter
`currency`. Event xác nhận immutable balanced journal đã commit. Nó chỉ mở quyền tạo
`account.capture.requested`; tự nó không cho phép Payment thành `SUCCEEDED`.
