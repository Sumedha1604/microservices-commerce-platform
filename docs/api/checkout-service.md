# Checkout Service API

Base URL: `http://localhost:8088`

All successful responses use the shared `ApiResponse` envelope. Errors use `ErrorResponse`.

## Create checkout

`POST /api/v1/checkouts`

Request:

```json
{
  "cartId": "b7c4e4c8-6b58-4c6b-af23-57bf16a2d06c"
}
```

Successful response: `201 Created`

```json
{
  "success": true,
  "message": "Success",
  "data": {
    "cartId": "b7c4e4c8-6b58-4c6b-af23-57bf16a2d06c",
    "orderId": "ad7a3a4f-f06d-44da-9f55-a7c8dfb9620e",
    "paymentId": "123e4567-e89b-12d3-a456-426614174000",
    "orderStatus": "PENDING",
    "paymentStatus": "PENDING",
    "total": 49.98,
    "currency": "USD"
  }
}
```

## Synchronous orchestration

The request executes synchronously in this order:

```
Cart -> Product -> Inventory -> Order -> Payment
```

Cart items are read first. Product data is authoritative for the order item name, SKU, price, and currency. Checkout requires active products, sufficient inventory, and one common currency.

Inventory reservations are made before order creation. If inventory fails, earlier reservations are released. If order creation fails, all successful reservations are released. If payment creation fails, Checkout Service attempts to cancel the created order and release all successful reservations. Compensation is best-effort and never replaces the original failure.

## Errors

- `400 BAD_REQUEST` — missing `cartId`, malformed JSON, empty cart, inactive product, mixed currencies, or insufficient inventory.
- A `CommerceException` from checkout orchestration uses its mapped status.
- `500 INTERNAL_SERVER_ERROR` — unexpected failures return a sanitized response.

## Current boundaries

- Service port is `8088` (`CHECKOUT_SERVER_PORT`).
- There is no local database.
- Kafka, Saga/distributed transaction handling, and Redis are not used.
- The cart is not cleared after checkout.
- There is no real payment provider integration or authentication yet.
