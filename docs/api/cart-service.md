# Cart Service API

Base path: `/api/v1/carts`.

- `POST /carts` — 201, body `CreateCartRequest` (`userId`)
- `GET /carts/{cartId}` — 200
- `GET /carts/user/{userId}` — 200
- `POST /carts/{cartId}/items` — 200, body `AddCartItemRequest` (`productId`, `quantity` positive); increases quantity if the item already exists in the cart
- `PATCH /carts/{cartId}/items/{productId}` — 200, body `UpdateCartItemQuantityRequest` (`quantity` positive)
- `DELETE /carts/{cartId}/items/{productId}` — 204
- `DELETE /carts/{cartId}/items` — 204, clears all items from the cart

Responses use the shared `ApiResponse`; validation and domain errors use `ErrorResponse`. Health is at `/actuator/health`; development OpenAPI is at `/swagger-ui/index.html`.

## Conflicts (`409 CONFLICT`)

- Creating a cart for a `userId` that already has one

## Not found (`404 RESOURCE_NOT_FOUND`)

- `cartId` does not exist for get, add item, update item, remove item, or clear
- `userId` does not exist for `GET /carts/user/{userId}`
- `productId` does not exist in the cart for update item or remove item

## Validation (`400 BAD_REQUEST`)

- Missing `userId` on create
- Missing `productId`, or missing/zero/negative `quantity`, on add item or update item
- Malformed UUID path variables
- Malformed JSON request bodies

## Current boundaries

Cart stores product references and quantities only. Checkout later loads authoritative Product data,
checks/reserves Inventory, snapshots prices into an Order, and creates a Payment. Cart Service itself
does not call those services, publish Kafka events, use Redis, clear a cart after checkout, validate
JWTs, or enforce cart ownership; authentication is applied at the API Gateway.
