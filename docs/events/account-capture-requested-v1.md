# `account.capture.requested` v1

Quyết định ordering nằm trong [ADR-011](../adr/ADR-011-ledger-capture-payment-success-ordering.md).

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `payment-service` |
| Consumer chính | Account boundary |
| Topic | `payflow.payment.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `account.capture.requested` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, `accountId`, `reservationId`, positive `amount` scale 4 và uppercase
three-letter `currency`. Payment chỉ tạo command này sau `ledger.payment-posted` khớp với
`account.funds-reserved`. Duplicate command cùng intent phải idempotent.
