# Documentation index

## Start here

- [System overview](architecture/system-overview.md) — services, ownership, dependencies, diagrams,
  security, messaging, and deployment model.
- [Project summary](project-summary.md) — portfolio-oriented scope, engineering decisions, and gaps.
- [Test strategy](testing/test-strategy.md) — test layers, commands, CI coverage, and known limits.
- [Root README](../README.md) — concise project introduction and quick start.
- [Frontend guide](../frontend/README.md) — routes, gateway contract map, auth behavior, local setup,
  testing, and known UI boundaries.

## Architecture and operations

- [Observability](architecture/observability.md)
- [Resilience](architecture/resilience.md)
- [Security model](security/security-model.md)
- [Kubernetes deployment](deployment/kubernetes.md)
- [GitHub Actions](ci/github-actions.md)
- [End-to-end environment](../tests/end-to-end/README.md)

## APIs

- [Authentication](api/auth-service.md)
- [User](api/user-service.md)
- [Product](api/product-service.md)
- [Inventory](api/inventory-service.md)
- [Cart](api/cart-service.md)
- [Order](api/order-service.md)
- [Payment](api/payment-service.md)
- [Checkout](api/checkout-service.md)
- [Notification](api/notification-service.md)
- [Search](api/search-service.md)
- [Recommendation](api/recommendation-service.md)

Gateway routes and access policy are summarized in the [system overview](architecture/system-overview.md)
and [security model](security/security-model.md).

## Events and operator guides

- [Payment events](events/payment-events.md)
- [Inventory compensation](events/inventory-compensation.md)
- [Notification events](events/notification-events.md)
- [Product events](events/product-events.md)
- [Dead-letter inspection and replay](events/dlt-operations.md)

## Data ownership

- [Auth database](database/auth-service.md)
- [User database](database/user-service.md)
- [Product database](database/product-service.md)
- [Inventory database](database/inventory-service.md)
- [Cart database](database/cart-service.md)
- [Order database](database/order-service.md)
- [Payment database](database/payment-service.md)
- [Notification database](database/notification-service.md)
- [Search database](database/search-service.md)
- [Recommendation database](database/recommendation-service.md)

Checkout and API Gateway are stateless and own no database.

## Architecture decisions

- [0001: Kafka payment/order events](decisions/0001-kafka-payment-order-events.md)
- [0002: Transactional outbox](decisions/0002-transactional-outbox.md)
- [0003: DLT inspection and replay](decisions/0003-dlt-inspection-and-replay.md)
- [0004: Order/inventory compensation](decisions/0004-order-inventory-compensation.md)
- [0005: Notification event consumer](decisions/0005-notification-event-consumer.md)
- [0006: Product search projection](decisions/0006-product-search-projection.md)
- [0007: Product recommendations](decisions/0007-product-recommendations.md)
- [0008: Gateway security and HTTP resilience](decisions/0008-gateway-security-and-http-resilience.md)
- [0009: Kubernetes and CI](decisions/0009-kubernetes-and-ci.md)
