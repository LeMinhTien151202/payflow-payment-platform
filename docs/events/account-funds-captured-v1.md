# `account.funds-captured` v1

Quyết định ordering nằm trong [ADR-011](../adr/ADR-011-ledger-capture-payment-success-ordering.md).

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | Account boundary |
| Consumer chính | `payment-service` |
| Topic | `payflow.account.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `account.funds-captured` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, `accountId`, `reservationId`, positive `amount` scale 4, uppercase
three-letter `currency` và `capturedAt`. Event xác nhận capture đã commit. Payment vẫn phải đối chiếu
ledger fact trước khi chuyển `SUCCEEDED`.
