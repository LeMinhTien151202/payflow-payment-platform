# `risk.assessment.completed` v1

Quyết định taxonomy và semantics nằm trong
[ADR-016](../adr/ADR-016-risk-assessment-event-taxonomy.md). File này là contract để
producer/consumer review mà không cần đọc code Java.

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `risk-service` |
| Consumer chính | `payment-service` |
| Topic | `payflow.risk.events.v1` |
| Kafka key | `paymentId` |
| Event type | `risk.assessment.completed` |
| Event version | `1` |
| Aggregate type | `PAYMENT` |

## Envelope mẫu

```json
{
  "eventId": "31734b31-8e75-4570-bdc6-979fa02ab446",
  "eventType": "risk.assessment.completed",
  "eventVersion": 1,
  "aggregateType": "PAYMENT",
  "aggregateId": "c73e17b5-aaca-48da-9ed5-bb0937499f01",
  "correlationId": "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
  "causationId": "51734b31-8e75-4570-bdc6-979fa02ab447",
  "producer": "risk-service",
  "occurredAt": "2026-07-28T08:00:00Z",
  "data": {
    "paymentId": "c73e17b5-aaca-48da-9ed5-bb0937499f01",
    "decision": "APPROVED",
    "score": 30,
    "level": "MEDIUM",
    "matchedRules": ["AMOUNT_HIGH"],
    "policyVersion": "risk-v1"
  }
}
```

## Payload rules

| Field | Type | Rule |
| --- | --- | --- |
| `paymentId` | UUID | Required; bằng envelope `aggregateId` và Kafka key |
| `decision` | enum | `APPROVED`, `REVIEW_REQUIRED`, `REJECTED` |
| `score` | integer | `0..100`, normalized theo ADR-015 |
| `level` | enum | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `matchedRules` | string array | Ordered, không null/rỗng/duplicate; có thể empty |
| `policyVersion` | string | Required, tối đa 30 ký tự |

Event được tạo do consume `payment.created`, vì vậy `correlationId` được giữ nguyên và
`causationId` là `eventId` của event đầu vào. Delivery là at-least-once; consumer phải
deduplicate bằng `(eventId, consumerName)` trong cùng transaction với business change.

## Compatibility

- Có thể thêm optional field nếu consumer cũ bỏ qua unknown field.
- Không đổi tên/xóa field, đổi enum meaning hoặc nới meaning của score trong v1.
- Breaking change cần version mới và compatibility plan.
