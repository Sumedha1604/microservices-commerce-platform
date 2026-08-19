# Order Service API

Base path: `/api/v1/orders`.

- `POST /orders` — 201, body `CreateOrderRequest` (`userId`, `currency`, `items[]`)
- `GET /orders/{orderId}` — 200
- `GET /orders/user/{userId}` — 200, returns a JSON array of orders, newest first
- `POST /orders/{orderId}/confirm` — 200, transitions `PENDING` → `CONFIRMED`
- `POST /orders/{orderId}/cancel` — 200, transitions `PENDING`/`CONFIRMED` → `CANCELLED`

`CreateOrderRequest.items[]` entries (`CreateOrderItemRequest`) carry `productId`, `productName`, `sku` (optional), `unitPrice` (`>= 0.00`), and `quantity` (`>= 1`).

Responses use the shared `ApiResponse`; validation and domain errors use `ErrorResponse`. Health is at `/actuator/health`; development OpenAPI is at `/swagger-ui/index.html`.

`OrderResponse` never includes the optimistic-locking `version` field.

## Conflicts (`409 CONFLICT`)

- Confirming an order that is not `PENDING` (already `CONFIRMED` or `CANCELLED`)
- Cancelling an order that is already `CANCELLED`
- Concurrent modification of the same order (optimistic locking failure) — response message: "Order was modified concurrently. Please retry."

## Not found (`404 RESOURCE_NOT_FOUND`)

- `orderId` does not exist for `GET`, confirm, or cancel

## Validation (`400 BAD_REQUEST`)

- Missing `userId`
- `currency` not exactly 3 characters, or blank
- Empty `items` list
- Invalid nested item (missing `productId`/`productName`, negative `unitPrice`, or `quantity < 1`)
- Malformed UUID path variables
- Malformed JSON request bodies

## Unexpected errors (`500 INTERNAL_SERVER_ERROR`)

Unhandled exceptions return a sanitized message with no SQL, stack trace, or exception class name exposed.
