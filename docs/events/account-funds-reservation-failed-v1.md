# `account.funds-reservation-failed` v1

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | Account boundary |
| Consumer chính | `payment-service` |
| Topic | `payflow.account.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `account.funds-reservation-failed` / `1` |
| Aggregate type | `PAYMENT` |

Payload gồm `paymentId`, `accountId` và uppercase stable `reasonCode`. Phase 1B core định nghĩa:

- `ACCOUNT_INSUFFICIENT_FUNDS`
- `ACCOUNT_FROZEN`
- `ACCOUNT_CLOSED`
- `ACCOUNT_RESERVATION_DEADLINE_EXPIRED`

Đây là definitive business outcome: balance và reservation không thay đổi. Payment chỉ nhận event
khi đang `RESERVING_FUNDS`, chuyển `FAILED` và phát `payment.failed` với cùng stable code. Internal
exception message, available balance và credential không được đưa lên wire.
