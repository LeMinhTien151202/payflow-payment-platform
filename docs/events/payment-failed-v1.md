# `payment.failed` v1

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `payment-service` |
| Consumers | `notification-service`, `reporting-service`; Account khi compensation contract yêu cầu |
| Topic | `payflow.payment.events.v1` |
| Kafka key | `paymentId` |
| Event type | `payment.failed` |
| Event version | `1` |
| Aggregate type | `PAYMENT` |

Payload:

```json
{
  "paymentId": "c73e17b5-aaca-48da-9ed5-bb0937499f01",
  "failureCode": "RISK_REJECTED",
  "failedAt": "2026-07-28T08:00:02Z"
}
```

`failureCode` là mã ổn định tối đa 100 ký tự, không phải raw exception/message. Với
ADR-016, risk rejection dùng `RISK_REJECTED`; không có reservation hoặc journal nào
được tạo. Các failure code cho Account/Ledger sẽ được khóa cùng contract tương ứng.

Delivery là at-least-once. Event giữ nguyên `eventId` khi republish và consumer phải
deduplicate bền vững. Breaking field/enum semantics cần event version mới.
