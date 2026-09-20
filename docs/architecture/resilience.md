# Synchronous HTTP resilience

## Audited boundary

Checkout Service is the platform's only synchronous outbound business HTTP client. It uses Spring
`RestClient` for Cart, Product, Inventory, Order, and Payment. Kafka retry/DLT/outbox behavior is
unchanged and is not wrapped in Resilience4j.

All checkout calls use a JDK HTTP client with a 1 second connection timeout and 2 second response
timeout. Configure these with `CHECKOUT_HTTP_CONNECT_TIMEOUT` and `CHECKOUT_HTTP_READ_TIMEOUT`.
Requests retain Micrometer observation so existing HTTP tracing continues.

## Retry and circuit policy

Only reads retry: cart GET, product GET, and inventory GET make at most two total attempts with a
100 ms delay. Retries apply to connection failures, timeouts, and downstream 5xx responses. Business
4xx responses are translated immediately and never retried.

Inventory reserve/release, order create/cancel, and payment create are never automatically retried.
They have timeouts and circuit breakers, but exactly one HTTP attempt per invocation. A timed-out
mutation has an ambiguous outcome; retrying it could duplicate a reservation, order, payment, or
compensation because these APIs do not carry a cross-service idempotency key.

Five separately observable breakers are used: `cartService`, `productService`, `inventoryService`,
`orderService`, and `paymentService`. Each uses a count-based window of 10 calls, at least 5 calls,
50% failure threshold, 10 second open duration, automatic half-open transition, and two permitted
half-open calls. Only transport failures, timeouts, and downstream 5xx responses count; business 4xx
does not.

## Failure semantics

- downstream business 400/401/403/404/409 retains the corresponding client status
- downstream 5xx becomes sanitized `502 DOWNSTREAM_BAD_GATEWAY`
- connection failure becomes sanitized `503 DOWNSTREAM_UNAVAILABLE`
- an open circuit becomes sanitized `503 DOWNSTREAM_CIRCUIT_OPEN`
- response timeout becomes sanitized `504 DOWNSTREAM_TIMEOUT`

Logs identify timeout, connection failure, and HTTP failure by dependency name without logging URLs,
authorization data, secrets, or response bodies. Resilience4j publishes circuit-breaker state/call and
retry metrics through the existing Micrometer Prometheus endpoint; labels are fixed breaker names.

## Checkout correctness

The existing order of side effects and best-effort compensation is unchanged. Reservations completed
before a later reserve/order failure are released. A payment failure triggers one order-cancel attempt
and one release attempt per recorded reservation. Resilience aspects do not retry any of those writes.
The known ambiguity remains: if a mutation succeeds downstream but its response times out, checkout
cannot know the outcome and compensation is best effort. Durable reservation records and idempotency
keys are future work; no exactly-once claim is made.
