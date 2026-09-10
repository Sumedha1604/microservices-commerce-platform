# End-to-End Tests

## Payment-creation failure compensation

Checkout payment-failure compensation is covered by unit tests. A full E2E test cannot currently force Payment creation to fail through the public APIs: the Payment service has no provider rejection behavior or supported failure configuration. Do not add a fake endpoint or configuration toggle solely for tests. Revisit this E2E case when a real payment provider or rejection behavior exists.

## Kafka event-flow tests (opt-in)

`CheckoutPaymentAuthorizationE2ETest` and `CheckoutPaymentFailedE2ETest` assert that a payment
transition reaches order-service over `payment.events.v1` and moves the order to CONFIRMED or
CANCELLED. The failure test also asserts the Saga compensation: the cancellation's
`InventoryReleaseRequested` crosses `order.compensation.v1` and the reserved quantity returns to
zero. The authorization test asserts the opposite - the reservation is still held several outbox
poll cycles after confirmation. They need the opt-in Kafka overlay, so they are skipped unless
`-De2e.kafka=true` is passed and stay out of the default suite:

```
docker compose \
  -f tests/end-to-end/compose.yml \
  -f infrastructure/kafka/compose.kafka.yml \
  up -d kafka payment order inventory product cart checkout

mvn -pl tests/end-to-end test -De2e.kafka=true
```

Both start from a real checkout - the same category/product/inventory/cart chain
`CheckoutHappyPathE2ETest` builds - and then continue past where it stops. That test asserts the
synchronous outcome (order and payment both PENDING) and holds with or without a broker; these
assert the asynchronous consequence, which only holds once the event has crossed the topic. The
order transition is asynchronous, so the terminal status is polled with a bounded timeout.

Low-level Kafka behaviour - deduplication, bounded retry, dead-lettering - is covered by the
order-service integration tests, not repeated here.
