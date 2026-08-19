# Order Service

Creates and tracks durable order records: an order and an immutable snapshot of the items purchased at the time of order creation. Backed by PostgreSQL and Flyway. `userId` and `productId` are UUID references only — there is no foreign key to User Service or Product Service, and no cross-service database access.

## Domain model

- **Order** — `userId`, `status` (`PENDING`, `CONFIRMED`, `CANCELLED`), `subtotal`, `total`, `currency`, optimistic-locking `version`
- **OrderItem** — a point-in-time snapshot of `productId`, `productName`, `sku`, `unitPrice`, `quantity`, and `lineTotal`, deleted automatically (`ON DELETE CASCADE`) when its order is deleted

Order items snapshot product name, SKU, and unit price at order creation time; they do not reference or refresh from Product Service, so later product changes do not alter existing orders.

## Order statuses

`PENDING` → `CONFIRMED` or `PENDING` → `CANCELLED` or `CONFIRMED` → `CANCELLED`. `CANCELLED` is terminal. Any other transition (including confirming a `CONFIRMED` or `CANCELLED` order, or cancelling a `CANCELLED` order) returns `409 CONFLICT`.

`PAID`, `SHIPPED`, and `DELIVERED` states are **not implemented**.

## Money calculation

All monetary values use `BigDecimal` mapped to `NUMERIC(19,2)` — no `float`/`double` is used anywhere in the money path. Each item's `lineTotal = unitPrice * quantity`, rounded half-up to 2 decimal places. `subtotal` is the sum of all `lineTotal`s. `total` currently equals `subtotal` — there is no tax, shipping, or discount calculation yet.

## API

All responses use the shared `ApiResponse` envelope; errors use `ErrorResponse`.

- `POST /api/v1/orders` — create an order (`201`)
- `GET /api/v1/orders/{orderId}` — get by order id
- `GET /api/v1/orders/user/{userId}` — get all orders for a user, newest first
- `POST /api/v1/orders/{orderId}/confirm` — transition `PENDING` → `CONFIRMED`
- `POST /api/v1/orders/{orderId}/cancel` — transition `PENDING`/`CONFIRMED` → `CANCELLED`

See [`docs/api/order-service.md`](../../docs/api/order-service.md) for full status/error mapping.

### Validation and error handling

Request DTOs validate required fields, currency format, and item quantities via Bean Validation. Validation failures, malformed JSON, and malformed UUID path variables return `400` with `ErrorResponse`; missing orders return `404`; invalid state transitions and concurrent modification conflicts return `409`; unhandled exceptions return a sanitized `500` with no SQL, stack traces, or exception class names exposed.

## Optimistic locking

`Order.version` is mapped with JPA `@Version`. If two requests load the same order and both attempt a transition (e.g. one confirms while another cancels), only the first commit succeeds; the losing request fails with an optimistic locking conflict, mapped to `409 CONFLICT` with the message "Order was modified concurrently. Please retry." `version` is never exposed in `OrderResponse`.

## Running locally

PostgreSQL must be reachable at the configured `ORDER_DB_URL`. Copy `.env.example`, adjust as needed (do not create or commit a real `.env`), export the variables, and run:

```
mvn -pl services/order-service -am spring-boot:run
```

Flyway runs `V1__create_order_schema.sql` automatically on startup, creating `orders` and `order_items`.

## Tests

```
mvn -pl services/order-service -am clean test
mvn -pl services/order-service -am clean verify
```

Integration coverage (`OrderPostgresIntegrationTest`) uses Testcontainers to run migrations, schema/constraint checks, repository and service behavior, money precision, and a concurrent optimistic-locking scenario against a real PostgreSQL 16 instance; Docker must be running for that test to execute.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `ORDER_DB_URL` | `jdbc:postgresql://localhost:5432/order_db` | Datasource URL |
| `ORDER_DB_USERNAME` | `order_user` | Datasource username |
| `ORDER_DB_PASSWORD` | `change-me` | Datasource password |
| `ORDER_SERVER_PORT` | `8086` | HTTP port |

## Docker

`docker build -f services/order-service/Dockerfile .` from the repository root builds a runnable image (multi-stage, matching the other services). The image has no Docker `HEALTHCHECK`, consistent with the rest of the platform: the minimal Java runtime has no HTTP client, so orchestration should probe `/actuator/health` instead.

## Implementation notes

- Service port: `8086`.
- `/actuator/health` is exposed for orchestration; other actuator endpoints are not.
- `userId` and `productId` are UUID references only — no foreign key to User Service or Product Service, no cross-service database access.
- **Not implemented yet:** Cart Service orchestration, Inventory reservation, Product lookup, Payment, `PAID`/`SHIPPED`/`DELIVERED` states, Kafka, Saga/distributed transactions, checkout orchestration, tax, shipping, discounts, and authentication/security.
