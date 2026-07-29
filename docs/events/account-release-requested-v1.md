# `account.release.requested` v1

Compensation contract được giới hạn bởi [ADR-011](../adr/ADR-011-ledger-capture-payment-success-ordering.md)
và [ADR-012](../adr/ADR-012-failure-recovery-before-service-split.md).

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `payment-service` |
| Consumer chính | Account boundary |
| Topic | `payflow.payment.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `account.release.requested` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, `accountId`, `reservationId`, positive `amount` scale 4,
uppercase three-letter `currency` và stable uppercase `reasonCode`.

Payment chỉ phát command khi Saga có reservation fact bền vững và **chưa có** journal fact.
Không được dùng command này để recovery sau `ledger.payment-posted`. Duplicate cùng intent phải
trả/no-op theo reservation hiện có, không cộng available balance lần hai.

