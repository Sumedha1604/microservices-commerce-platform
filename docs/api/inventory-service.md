# Inventory Service API

Base path: `/api/v1/inventory`.

- `POST /inventory` — 201, body `CreateInventoryRequest` (`productId`, `quantity`)
- `GET /inventory/{inventoryId}` — 200
- `GET /inventory/product/{productId}` — 200
- `PATCH /inventory/{inventoryId}/quantity` — 200, body `UpdateInventoryQuantityRequest` (`quantity`)
- `POST /inventory/{inventoryId}/reserve` — 200, body `StockQuantityRequest` (`quantity`, positive)
- `POST /inventory/{inventoryId}/release` — 200, body `StockQuantityRequest` (`quantity`, positive)

Responses use the shared `ApiResponse`; validation and domain errors use `ErrorResponse`. Health is at `/actuator/health`; development OpenAPI is at `/swagger-ui/index.html`.

## Conflicts (`409 CONFLICT`)

- Creating inventory for a `productId` that already has a record
- Reserving more than the current available quantity (`quantity - reservedQuantity`)
- Releasing more than the current reserved quantity
- Updating total quantity below the current reserved quantity

## Not found (`404 RESOURCE_NOT_FOUND`)

- `inventoryId` does not exist for `GET`, `PATCH`, reserve, or release
- `productId` does not exist for `GET /inventory/product/{productId}`

## Validation (`400 BAD_REQUEST`)

- Missing/negative `quantity` on create or update
- Missing, zero, or negative `quantity` on reserve/release
- Malformed UUID path variables
- Malformed JSON request bodies
