# Cart Service

Manages shopping carts: a cart per user and the line items within it. Backed by PostgreSQL and Flyway. `userId` and `productId` are UUID references only — there is no foreign key to User Service or Product Service, and no cross-service database access.

## Domain model

- **Cart** — `userId` (unique, one cart per user), owns a collection of items
- **CartItem** — `productId`, `quantity` (positive integer), unique per `(cartId, productId)`, deleted automatically (`ON DELETE CASCADE`) when its cart is deleted

## API

All responses use the shared `ApiResponse` envelope; errors use `ErrorResponse`.

- `POST /api/v1/carts` — create a cart (`201`)
- `GET /api/v1/carts/{cartId}` — get by cart id
- `GET /api/v1/carts/user/{userId}` — get by user id
- `POST /api/v1/carts/{cartId}/items` — add an item, or increase quantity if the product is already in the cart
- `PATCH /api/v1/carts/{cartId}/items/{productId}` — set an item's quantity
- `DELETE /api/v1/carts/{cartId}/items/{productId}` — remove an item (`204`)
- `DELETE /api/v1/carts/{cartId}/items` — clear all items from a cart (`204`)

### Business invariants

- One cart per `userId`; a duplicate create returns `409 CONFLICT`
- Adding an item that already exists in the cart increases its quantity instead of creating a duplicate row
- Item `quantity` must be a positive integer (enforced by Bean Validation and a database check constraint)
- Deleting a cart cascades to delete its items

### Validation and error handling

Request DTOs validate required fields and quantity bounds via Bean Validation. Validation failures and malformed JSON return `400` with `ErrorResponse`; domain errors (not found, conflicting cart) return the status carried by `CommerceException`; unhandled exceptions return a sanitized `500`.

## Running locally

PostgreSQL must be reachable at the configured `CART_DB_URL`. Copy `.env.example`, adjust as needed (do not create or commit a real `.env`), export the variables, and run:

```
mvn -pl services/cart-service -am spring-boot:run
```

Flyway runs `V1__create_cart_schema.sql` automatically on startup, creating `carts` and `cart_items`.

## Tests

```
mvn -pl services/cart-service -am clean test
mvn -pl services/cart-service -am clean verify
```

Integration coverage (`CartPostgresIntegrationTest`) uses Testcontainers to run migrations and repository/service behavior against a real PostgreSQL 16 instance; Docker must be running for that test to execute.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `CART_DB_URL` | `jdbc:postgresql://localhost:5432/cart_db` | Datasource URL |
| `CART_DB_USERNAME` | `cart_user` | Datasource username |
| `CART_DB_PASSWORD` | `change-me` | Datasource password |
| `CART_SERVER_PORT` | `8085` | HTTP port |

## Docker

`docker build -f services/cart-service/Dockerfile .` from the repository root builds a runnable image (multi-stage, matching the other services). The image has no Docker `HEALTHCHECK`, consistent with the rest of the platform: the minimal Java runtime has no HTTP client, so orchestration should probe `/actuator/health` instead.

## Implementation notes

- Service port: `8085`.
- `/actuator/health` is exposed for orchestration; other actuator endpoints are not.
- `userId` and `productId` are UUID references only — no foreign key to User Service or Product Service, no cross-service database access.
- **Not implemented yet:** Product Service validation of `productId`, Inventory Service availability checks, price snapshots, checkout, order creation, Kafka events, Redis, and authentication/security.
