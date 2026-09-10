# Notification Service API

Base path: `/api/v1/notifications`. Read-only. Also routed through the API gateway
(`/api/v1/notifications/**` → notification-service, port 8089).

- `GET /notifications/{notificationId}`: 200, one notification
- `GET /notifications`: 200, paged and filtered list, newest first
- `GET /notifications/order/{orderId}`: 200, paged list for one order, newest first

There is **no create, update, delete or send endpoint**. Notifications are written only by the
`payment.events.v1` consumer (see [../events/notification-events.md](../events/notification-events.md)),
so no caller can fabricate one. Other methods return `405 METHOD_NOT_ALLOWED`.

Responses use the shared `ApiResponse`; errors use `ErrorResponse`. Health is at `/actuator/health`,
Prometheus metrics at `/actuator/prometheus`, and development OpenAPI at `/swagger-ui/index.html`.

## List parameters

| Parameter | Type | Default | Notes |
|---|---|---|---|
| `orderId` | UUID | none | `GET /notifications` only |
| `userId` | UUID | none | `GET /notifications` only |
| `eventType` | string | none | `PaymentAuthorized` or `PaymentFailed`; blank means no filter |
| `notificationType` | enum | none | `PAYMENT_AUTHORIZED`, `PAYMENT_FAILED` |
| `status` | enum | none | `CREATED` |
| `page` | int | `0` | zero-based, `>= 0` |
| `size` | int | `20` | `1..100` |

Every filter is optional and they combine with AND. Sorting is fixed: `createdAt` descending, then
`notificationId` ascending, so pages never repeat or skip rows.

The list `data` is a `PageResponse`: `items`, `page`, `size`, `totalElements`, `totalPages`,
`hasNext`, `hasPrevious`.

## `NotificationResponse`

| Field | Type | Notes |
|---|---|---|
| `notificationId` | UUID | |
| `eventId` | UUID | Source payment event; the deduplication key |
| `eventType` | string | `PaymentAuthorized` / `PaymentFailed` |
| `orderId` | UUID | Reference only |
| `paymentId` | UUID | Reference only |
| `userId` | UUID | Recipient, reference only; no contact details exist |
| `channel` | enum | `INTERNAL`, the only channel; no provider is attached |
| `notificationType` | enum | `PAYMENT_AUTHORIZED` / `PAYMENT_FAILED` |
| `subject` | string | |
| `message` | string | |
| `status` | enum | `CREATED`: recorded, **not** sent |
| `occurredAt` | instant | When the payment event was raised |
| `createdAt` | instant | When the notification was recorded |
| `updatedAt` | instant | Equal to `createdAt` today (no transitions yet) |

Example:

```json
{
  "success": true,
  "message": "Request completed successfully",
  "data": {
    "notificationId": "5a0c…",
    "eventId": "f7d4…",
    "eventType": "PaymentAuthorized",
    "orderId": "240e…",
    "paymentId": "fe69…",
    "userId": "731b…",
    "channel": "INTERNAL",
    "notificationType": "PAYMENT_AUTHORIZED",
    "subject": "Payment authorized for order 240e…",
    "message": "Your payment of 59.97 USD for order 240e… was authorized (payment fe69…).",
    "status": "CREATED",
    "occurredAt": "2026-09-11T10:15:30.123456Z",
    "createdAt": "2026-09-11T10:15:30.456789Z",
    "updatedAt": "2026-09-11T10:15:30.456789Z"
  },
  "timestamp": "…"
}
```

## Not found (`404 RESOURCE_NOT_FOUND`)

- `notificationId` does not exist
- Unknown paths

A list or by-order query that matches nothing is `200` with an empty page, not `404`.

## Validation (`400 BAD_REQUEST`)

- Malformed UUID in a path variable or in the `orderId`/`userId` filter
- Unknown `notificationType` or `status` value (e.g. `SENT`)
- `eventType` other than `PaymentAuthorized`/`PaymentFailed`
- `page < 0`, or `size` outside `1..100`
- Non-integer `page`/`size`

## Unexpected errors (`500 INTERNAL_SERVER_ERROR`)

Sanitized message; no SQL, stack trace or exception class is exposed.

## Security

**Not authorization-protected.** No service API on the platform validates tokens yet, and this one
is no exception. Notifications identify users and describe their payments, so before production
these routes need authentication and per-user scoping (a user may read only their own `userId`'s
notifications), or restriction to operators.
