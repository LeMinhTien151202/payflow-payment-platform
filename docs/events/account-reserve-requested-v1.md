# `account.reserve.requested` v1

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | `payment-service` |
| Consumer chính | Account boundary |
| Topic | `payflow.payment.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `account.reserve.requested` / `1` |
| Aggregate type | `PAYMENT` |

Payload gồm `paymentId`, `accountId`, positive `amount` chuẩn hóa scale 4, uppercase three-letter
`currency` và absolute `expiresAt`. `expiresAt` phải sau thời điểm command được tạo. Account dùng
deadline này cho reservation nhưng dùng clock cục bộ khi xử lý; command tới sau deadline phải trả
failure fact và không thay đổi balance.

Account xử lý command trong một local transaction: inbox insert-if-new, atomic balance claim,
reservation unique theo `payment_id` và outcome outbox. Duplicate cùng intent không được giữ tiền lần
hai; khác amount/currency/account/deadline là conflict cần điều tra.
