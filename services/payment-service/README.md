# Payment Service

Creates and tracks standalone payment records for an order: one payment per order, moving through a fixed status lifecycle. Backed by PostgreSQL and Flyway. `orderId` and `userId` are UUID references only — there is no foreign key to Order Service or User Service, and no cross-service database access.

## Domain model

- **Payment** — `orderId` (unique), `userId`, `status` (`PENDING`, `AUTHORIZED`, `CAPTURED`, `FAILED`, `CANCELLED`, `REFUNDED`), `amount`, `currency`, `provider`, `providerReference`, `failureReason`, optimistic-locking `version`

`provider` and `providerReference` are opaque strings supplied by the caller on `authorize`; this service does not call out to any real payment provider. `failureReason` is supplied by the caller on `fail`.

## Payment statuses and transitions

```
PENDING -> AUTHORIZED -> CAPTURED -> REFUNDED
PENDING -> FAILED
AUTHORIZED -> FAILED
PENDING -> CANCELLED
AUTHORIZED -> CANCELLED
```

`FAILED`, `CANCELLED`, and `REFUNDED` are terminal. Any other transition returns `409 CONFLICT`.

## Money calculation

All monetary values use `BigDecimal` mapped to `NUMERIC(19,2)` — no `float`/`double` is used anywhere in the money path. `amount` is rounded half-up to 2 decimal places on creation; the returned amount always equals the persisted amount.

## API

All responses use the shared `ApiResponse` envelope; errors use `ErrorResponse`.

- `POST /api/v1/payments` — create a payment (`201`)
- `GET /api/v1/payments/{paymentId}` — get by payment id
- `GET /api/v1/payments/order/{orderId}` — get the payment for an order
- `GET /api/v1/payments/user/{userId}` — get all payments for a user, newest first
- `POST /api/v1/payments/{paymentId}/authorize` — transition `PENDING` → `AUTHORIZED`
- `POST /api/v1/payments/{paymentId}/capture` — transition `AUTHORIZED` → `CAPTURED`
- `POST /api/v1/payments/{paymentId}/fail` — transition `PENDING`/`AUTHORIZED` → `FAILED`
- `POST /api/v1/payments/{paymentId}/cancel` — transition `PENDING`/`AUTHORIZED` → `CANCELLED`
- `POST /api/v1/payments/{paymentId}/refund` — transition `CAPTURED` → `REFUNDED`

See [`docs/api/payment-service.md`](../../docs/api/payment-service.md) for full status/error mapping.

### Validation and error handling

Request DTOs validate required fields, currency format, and amount via Bean Validation. Validation failures, malformed JSON, and malformed UUID path variables return `400` with `ErrorResponse`; missing payments return `404`; invalid state transitions and concurrent modification conflicts return `409`; unhandled exceptions return a sanitized `500` with no SQL, stack traces, or exception class names exposed.

## Optimistic locking

`Payment.version` is mapped with JPA `@Version`. If two requests load the same payment and both attempt a transition (e.g. one authorizes while another cancels), only the first commit succeeds; the losing request fails with an optimistic locking conflict, mapped to `409 CONFLICT` with the message "Payment was modified concurrently. Please retry." `version` is never exposed in `PaymentResponse`.

## Running locally

PostgreSQL must be reachable at the configured `PAYMENT_DB_URL`. Copy `.env.example`, adjust as needed (do not create or commit a real `.env`), export the variables, and run:

```
mvn -pl services/payment-service -am spring-boot:run
```

Flyway runs `V1__create_payment_schema.sql` automatically on startup, creating `payments`.

## Tests

```
mvn -pl services/payment-service -am clean test
mvn -pl services/payment-service -am clean verify
```

Integration coverage (`PaymentPostgresIntegrationTest`) uses Testcontainers to run migrations, schema/constraint checks, repository and service behavior, money precision, and a concurrent optimistic-locking scenario against a real PostgreSQL 16 instance; Docker must be running for that test to execute.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `PAYMENT_DB_URL` | `jdbc:postgresql://localhost:5432/payment_db` | Datasource URL |
| `PAYMENT_DB_USERNAME` | `payment_user` | Datasource username |
| `PAYMENT_DB_PASSWORD` | `change-me` | Datasource password |
| `PAYMENT_SERVER_PORT` | `8087` | HTTP port |

## Docker

`docker build -f services/payment-service/Dockerfile .` from the repository root builds a runnable image (multi-stage, matching the other services). The image has no Docker `HEALTHCHECK`, consistent with the rest of the platform: the minimal Java runtime has no HTTP client, so orchestration should probe `/actuator/health` instead.

## Implementation notes

- Service port: `8087`.
- `/actuator/health` is exposed for orchestration; other actuator endpoints are not.
- `orderId` and `userId` are UUID references only — no foreign key to Order Service or User Service, no cross-service database access.
- **Not implemented:** Order Service integration, a real payment provider (Stripe/Adyen/PayPal), real money movement, webhook processing, Kafka, Saga/distributed transactions, checkout orchestration, inventory communication, authentication, payment retries, and provider reconciliation.
