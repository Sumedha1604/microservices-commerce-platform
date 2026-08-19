# Payment Service API

Base path: `/api/v1/payments`.

- `POST /payments` — 201, body `CreatePaymentRequest` (`orderId`, `userId`, `amount`, `currency`)
- `GET /payments/{paymentId}` — 200
- `GET /payments/order/{orderId}` — 200, returns the single payment for that order
- `GET /payments/user/{userId}` — 200, returns a JSON array of payments, newest first
- `POST /payments/{paymentId}/authorize` — 200, body `AuthorizePaymentRequest` (`provider`, `providerReference`), transitions `PENDING` → `AUTHORIZED`
- `POST /payments/{paymentId}/capture` — 200, transitions `AUTHORIZED` → `CAPTURED`
- `POST /payments/{paymentId}/fail` — 200, body `FailPaymentRequest` (`reason`), transitions `PENDING`/`AUTHORIZED` → `FAILED`
- `POST /payments/{paymentId}/cancel` — 200, transitions `PENDING`/`AUTHORIZED` → `CANCELLED`
- `POST /payments/{paymentId}/refund` — 200, transitions `CAPTURED` → `REFUNDED`

`CreatePaymentRequest.amount` must be `>= 0.00`; `currency` must be exactly 3 letters. `AuthorizePaymentRequest.provider` and `providerReference` are opaque caller-supplied strings — no real provider is called. `FailPaymentRequest.reason` is required, max 500 characters.

Responses use the shared `ApiResponse`; validation and domain errors use `ErrorResponse`. Health is at `/actuator/health`; development OpenAPI is at `/swagger-ui/index.html`.

`PaymentResponse` never includes the optimistic-locking `version` field.

## Conflicts (`409 CONFLICT`)

- Creating a payment for an `orderId` that already has one
- Authorizing a payment that is not `PENDING`
- Capturing a payment that is not `AUTHORIZED`
- Failing a payment that is not `PENDING` or `AUTHORIZED`
- Cancelling a payment that is not `PENDING` or `AUTHORIZED`
- Refunding a payment that is not `CAPTURED`
- Concurrent modification of the same payment (optimistic locking failure) — response message: "Payment was modified concurrently. Please retry."

## Not found (`404 RESOURCE_NOT_FOUND`)

- `paymentId` does not exist for `GET`, authorize, capture, fail, cancel, or refund
- `orderId` has no payment for `GET /payments/order/{orderId}`

## Validation (`400 BAD_REQUEST`)

- Missing `orderId` or `userId`
- `amount` missing or negative
- `currency` not exactly 3 letters, or blank
- Missing/blank `provider` or `providerReference` on authorize
- Missing/blank or over-length `reason` on fail
- Malformed UUID path variables
- Malformed JSON request bodies

## Unexpected errors (`500 INTERNAL_SERVER_ERROR`)

Unhandled exceptions return a sanitized message with no SQL, stack trace, or exception class name exposed.

## Not implemented

Order Service integration, a real payment provider (Stripe/Adyen/PayPal), real money movement, webhook processing, Kafka, Saga/distributed transactions, checkout orchestration, inventory communication, authentication, payment retries, and provider reconciliation.
