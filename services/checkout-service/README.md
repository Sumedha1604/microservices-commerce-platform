# Checkout Service

Checkout Service coordinates a synchronous checkout across the existing Cart, Product, Inventory, Order, and Payment services. It listens on port `8088` by default.

## Flow

```
Cart -> Product -> Inventory -> Order -> Payment
```

1. Load the cart and reject an empty cart.
2. Load each product and require it to be `ACTIVE` and active.
3. Require a common product currency.
4. Check and reserve inventory for each cart item.
5. Create an order from the cart user and authoritative product data.
6. Create a payment for the order total.

## Compensation

Checkout uses best-effort compensation for synchronous downstream failures:

- If inventory lookup or reservation fails, earlier successful inventory reservations are released.
- If order creation fails, all successful inventory reservations are released.
- If payment creation fails, the created order is cancelled and all successful inventory reservations are released.

The original failure is preserved if a compensation request also fails.

## API

`POST /api/v1/checkouts` accepts a `cartId` and returns the cart, order, and payment identifiers and statuses in the shared `ApiResponse` envelope. See [`docs/api/checkout-service.md`](../../docs/api/checkout-service.md) for details.

## Runtime boundaries

- No local database is used.
- Checkout itself uses no Kafka, persistent Saga, or Redis. Payment-driven order and inventory
  compensation is event-driven in the owning services.
- Checkout does not clear the cart.
- Gateway authentication protects checkout at the ingress boundary; direct service access remains a deployment concern.
- No real payment provider is implemented.

## HTTP resilience

Every downstream call has finite connection and response timeouts. Cart, product, and inventory GETs
make at most two attempts for transport/5xx failures. Mutations are single-attempt because retrying an
ambiguous timeout could duplicate side effects. Separate Resilience4j circuit breakers and Prometheus
metrics cover all five dependencies. See [`docs/architecture/resilience.md`](../../docs/architecture/resilience.md).

## Running locally

```
mvn -pl services/checkout-service -am spring-boot:run
```

## Environment variables

| Variable | Default |
|---|---|
| `CHECKOUT_SERVER_PORT` | `8088` |
| `CART_SERVICE_URL` | `http://localhost:8085` |
| `PRODUCT_SERVICE_URL` | `http://localhost:8083` |
| `INVENTORY_SERVICE_URL` | `http://localhost:8084` |
| `ORDER_SERVICE_URL` | `http://localhost:8086` |
| `PAYMENT_SERVICE_URL` | `http://localhost:8087` |
| `CHECKOUT_HTTP_CONNECT_TIMEOUT` | `1s` |
| `CHECKOUT_HTTP_READ_TIMEOUT` | `2s` |
