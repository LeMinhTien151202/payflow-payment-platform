# `account.funds-released` v1

| Thuộc tính | Giá trị |
| --- | --- |
| Owner/producer | Account boundary |
| Consumer chính | `payment-service` |
| Topic | `payflow.account.events.v1` |
| Kafka key | `paymentId` |
| Event type/version | `account.funds-released` / `1` |
| Aggregate type | `PAYMENT` |

Payload v1 gồm `paymentId`, `accountId`, `reservationId`, `amount`, `currency`, `reasonCode` và
`releasedAt`. Đây là fact chỉ được tạo sau khi local transaction đã chuyển reservation `ACTIVE ->
RELEASED` và khôi phục available balance.

Payment chỉ hoàn tất compensation nếu toàn bộ identity, money và `reasonCode` khớp command đang
chờ. Redelivery giữ nguyên `eventId`; duplicate intent không tạo một release fact thứ hai.

