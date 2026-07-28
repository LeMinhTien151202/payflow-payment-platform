# `payment.succeeded` v1

Quyết định ordering nằm trong [ADR-011](../adr/ADR-011-ledger-capture-payment-success-ordering.md).

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `payment-service` |
| Consumers | Notification, Reporting, Settlement |
| Topic | `payflow.payment.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `payment.succeeded` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, `merchantId`, `customerId`, positive `amount` scale 4, uppercase
three-letter `currency` và `completedAt`. Đây là terminal business fact chỉ được append cùng local
transaction chuyển Payment `PROCESSING -> SUCCEEDED`, sau khi Payment có cả ledger-posted và
funds-captured acknowledgement khớp. Account không dùng event này làm capture command.
