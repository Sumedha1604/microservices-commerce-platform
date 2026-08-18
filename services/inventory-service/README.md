# Inventory Service

Tracks stock for products: total quantity, reserved quantity, and derived available quantity. One inventory record exists per `productId`. Backed by PostgreSQL and Flyway. `productId` is a UUID reference only — there is no foreign key to Product Service and no cross-service database access.

## Domain model

- **Inventory** — `productId` (unique), `quantity` (total stock), `reservedQuantity` (stock held against pending demand)
- `availableQuantity = quantity - reservedQuantity` is derived, not stored

## API

All responses use the shared `ApiResponse` envelope; errors use `ErrorResponse`.

- `POST /api/v1/inventory` — create (`201`)
- `GET /api/v1/inventory/{inventoryId}` — get by inventory id
- `GET /api/v1/inventory/product/{productId}` — get by product id
- `PATCH /api/v1/inventory/{inventoryId}/quantity` — update total quantity
- `POST /api/v1/inventory/{inventoryId}/reserve` — reserve stock
- `POST /api/v1/inventory/{inventoryId}/release` — release previously reserved stock

### Business invariants

- One inventory record per `productId`; a duplicate create returns `409 CONFLICT`
- New inventory starts with `reservedQuantity = 0`
- `quantity >= 0` and `reservedQuantity >= 0` and `reservedQuantity <= quantity` (enforced by DB check constraints and application logic)
- Reserving more than the currently available quantity returns `409 CONFLICT`
- Releasing more than the currently reserved quantity returns `409 CONFLICT`
- Updating total quantity below the currently reserved quantity returns `409 CONFLICT`
- Reserve/release never change total `quantity`, only `reservedQuantity`

### Validation and error handling

Request DTOs validate required fields and quantity bounds via Bean Validation (`StockQuantityRequest` requires a positive integer). Validation failures and malformed JSON return `400` with `ErrorResponse`; domain errors (not found, conflicting quantities) return the status carried by `CommerceException`; unhandled exceptions return a sanitized `500`.

## Running locally

PostgreSQL must be reachable at the configured `INVENTORY_DB_URL`. Copy `.env.example`, adjust as needed (do not create or commit a real `.env`), export the variables, and run:

```
mvn -pl services/inventory-service -am spring-boot:run
```

Flyway runs `V1__create_inventory_schema.sql` automatically on startup, creating `inventory`.

## Tests

```
mvn -pl services/inventory-service -am clean test
mvn -pl services/inventory-service -am clean verify
```

Integration coverage (`InventoryPostgresIntegrationTest`) uses Testcontainers to run migrations and repository/service behavior against a real PostgreSQL 16 instance; Docker must be running for that test to execute.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `INVENTORY_DB_URL` | `jdbc:postgresql://localhost:5432/inventory_db` | Datasource URL |
| `INVENTORY_DB_USERNAME` | `inventory_user` | Datasource username |
| `INVENTORY_DB_PASSWORD` | `change-me` | Datasource password |
| `INVENTORY_SERVER_PORT` | `8084` | HTTP port |

## Docker

`docker build -f services/inventory-service/Dockerfile .` from the repository root builds a runnable image (multi-stage, matching the other services). The image has no Docker `HEALTHCHECK`, consistent with the rest of the platform: the minimal Java runtime has no HTTP client, so orchestration should probe `/actuator/health` instead.

## Implementation notes

- Service port: `8084`.
- `/actuator/health` is exposed for orchestration; other actuator endpoints are not.
- `productId` is a UUID reference only — no foreign key to Product Service, no cross-service database access, no Product Service HTTP client.
- Planned but not implemented: Kafka events, Redis, distributed locking, warehouses, stock adjustment history, and cart/order/payment integration.
