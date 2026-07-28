# `account.funds-reserved` v1

Quyết định ordering nằm trong [ADR-011](../adr/ADR-011-ledger-capture-payment-success-ordering.md).

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | Account boundary |
| Consumer chính | `payment-service` |
| Topic | `payflow.account.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `account.funds-reserved` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, `accountId`, `reservationId`, positive `amount` chuẩn hóa scale 4 và
uppercase three-letter `currency`. Ba ID là bắt buộc. Envelope `aggregateId` và Kafka key phải bằng
`paymentId`. Event này là fact reservation đã commit; nó chưa có nghĩa funds đã captured.
