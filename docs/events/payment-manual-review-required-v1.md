# `payment.manual-review-required` v1

Semantics được khóa trong [ADR-018](../adr/ADR-018-payment-manual-review-contract.md).

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `payment-service` |
| Consumers | operations projection, notification/reporting tương lai |
| Topic | `payflow.payment.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `payment.manual-review-required` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, `sagaStep` và stable uppercase `reasonCode`. Event nói rằng automation đã
dừng; nó không khẳng định giao dịch thành công hay thất bại.

Operations phải đọc Saga facts (`reservationId`, `journalId`, current step) trước resolution. Sau khi
có journal fact, tự động release reservation bị cấm. Redelivery phải được deduplicate theo `eventId`.

