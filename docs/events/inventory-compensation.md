# Inventory compensation: releasing stock after a failed payment

Operator guide for `order.compensation.v1`. It covers why the event exists, what happens when it
is delivered twice, what happens when it cannot be applied, and what this flow deliberately does
**not** guarantee.

See also: [payment-events.md](payment-events.md) for the upstream contract, and
[../decisions/0004-order-inventory-compensation.md](../decisions/0004-order-inventory-compensation.md)
for why it is built this way.

## Why this exists

Checkout reserves inventory before the payment is authorized. Authorization is asynchronous, so by
the time it fails, checkout has long since returned. Without this flow the reserved units stayed
held forever — stock that no customer could buy and no report showed as lost.

## The two flows

**Success — nothing is compensated:**

```
checkout: reserve inventory -> order PENDING -> payment PENDING
payment authorized -> PaymentAuthorized on payment.events.v1
order-service: order CONFIRMED
   (no compensation event; the reservation stays exactly as it was)
```

**Failure — stock comes back:**

```
checkout: reserve inventory -> order PENDING -> payment PENDING
payment fails -> PaymentFailed on payment.events.v1
order-service, in ONE transaction:
      order CANCELLED
    + processed_event marker
    + order_outbox_event row (InventoryReleaseRequested)
order-service outbox publisher (later, asynchronously):
      -> order.compensation.v1, keyed by orderId
inventory-service, in ONE transaction:
      processed_event marker
    + reserved_quantity reduced per line
```

## The event

Topic `order.compensation.v1`, key `orderId`, schema version 1.

```json
{
  "eventId": "…uuid",
  "eventType": "InventoryReleaseRequested",
  "schemaVersion": 1,
  "occurredAt": "2026-09-10T12:00:00Z",
  "payload": {
    "orderId": "…uuid",
    "reason": "Payment failed: card declined",
    "lines": [ { "productId": "…uuid", "quantity": 3 } ]
  }
}
```

**Why lines and not a reservation id.** There are no reservation records in this platform — a
reservation is the `reserved_quantity` counter on the inventory row, and the reserve API takes only
a quantity. The order's `order_items` are the only durable record of what was reserved, so that is
what the event carries. `productId` resolves to an inventory row because `product_id` is unique
there.

**Ownership.** order-service publishes; inventory-service consumes. inventory-service does **not**
consume payment events — "a payment failed" is not an inventory concern.

## When compensation is emitted — and when it is not

| Situation | Compensation? |
| --- | --- |
| `PaymentFailed` cancels a `PENDING` or `CONFIRMED` order | **Yes**, exactly one event |
| `PaymentAuthorized` confirms an order | No |
| `PaymentFailed` redelivered with the same `eventId` | No — deduplicated before anything runs |
| A *different* `PaymentFailed` for an already-`CANCELLED` order | No — it cancelled nothing |
| `POST /api/v1/orders/{id}/cancel` (the HTTP endpoint) | **No** — see below |
| A cancelled order with no line items | No — nothing to release |

**The HTTP cancel path is deliberately silent.** checkout-service calls it in its own failure
handler, immediately after releasing those reservations synchronously. Emitting an event there
would release the same stock twice. Compensation is attached to the `PaymentFailed` consumer path
only, never to `Order.cancel()` itself.

## Reliability

### order-service side: transactional outbox

The order transition and the outbox row commit together or not at all. **No Kafka call happens
inside that transaction.** If the outbox insert fails, the cancellation rolls back with it — an
order is never cancelled without the promise to release its inventory.

The publisher then:

- claims `PENDING` rows with `for update skip locked`, so two publishers never claim one row;
- waits for the broker's acknowledgement before marking a row `PUBLISHED`;
- on failure, leaves the row `PENDING` with its `eventId` and payload **untouched** and schedules
  an exponential backoff (`attempt_count` records failures, and drives that backoff);
- republishes the stored bytes verbatim — it never re-renders the event.

The `eventId` is minted once, inside the business transaction, and reused by every attempt forever.
That is exactly what makes the crash window safe.

### inventory-service side: deduplication

Every applied `eventId` is claimed in `processed_event` **in the same transaction as the stock
change**, *before* any stock moves, with a guarded insert:

```sql
insert into processed_event (...) values (...) on conflict (event_id) do nothing
```

It returns 1 (this transaction owns the event) or 0 (already applied). So:

- a redelivery gets 0 and is a no-op, acknowledged normally — no retry, no DLT entry;
- two workers racing the same event: the second insert **waits** for the first transaction, then
  gets 0 if it committed (duplicate) or 1 if it rolled back (this worker applies it). Neither
  outcome is an exception, so nothing has to guess that an integrity error "was probably a
  duplicate" — and a genuine integrity failure can never be acked away as one;
- a release that fails rolls the marker back with it, so the retry (or an operator replay) is not
  silently swallowed.

Stock is changed under a row lock: each inventory row is loaded `SELECT … FOR UPDATE`, so the
"is this much reserved?" check and the decrement cannot be split by another writer. Lines are
merged per product and locked in ascending `productId` order, so two compensations touching the
same products can neither deadlock nor knock each other onto the retry path.

A multi-line release is all-or-nothing: if the third line's product is unknown, the first two
decrements roll back with it. Stock is never left half-restored.

Kafka producer idempotence is **not** relied on: it only deduplicates retries inside one producer
session, and says nothing about an outbox republish after a restart.

## Delivery semantics

**This is at-least-once plus consumer-side deduplication. It is not exactly-once, and nothing here
claims to be.**

The outbox can genuinely publish a row twice — a crash after the broker acknowledges but before the
`PUBLISHED` update guarantees it. `processed_event` in inventory-service is what makes that
harmless. Idempotency lives there, not in the producer, and **not** in Kafka's producer
idempotence, which only deduplicates retries within a single producer session.

## Ordering

Per **order** only. The outbox claims at most one `PENDING` row per `aggregate_id`, so a later
event for one order can never overtake an earlier pending one. An order whose event is in backoff
holds back only its own successors — every other order keeps publishing.

There is no ordering guarantee across different orders, and none is needed: compensation for one
order is independent of every other.

## Failure handling and the dead-letter topic

The consumer retries 3 times (initial delivery plus 2), then dead-letters to
`order.compensation.v1.DLT`. Some failures skip the retries entirely:

| Failure | Retried? | Why |
| --- | --- | --- |
| Database unavailable, lock timeout, optimistic clash with a concurrent HTTP reserve/release | **Yes**, then DLT | Transient; the next attempt may well succeed |
| Same `eventId` already applied | Not a failure | Acknowledged as a duplicate; never retried or dead-lettered |
| Malformed JSON, bad envelope | No — DLT at once | Cannot become readable |
| Unsupported `schemaVersion` | No — DLT at once | This consumer does not understand it and must not guess |
| Unknown `eventType` | No — DLT at once | Not ours to act on |
| Payload with no `orderId`, no lines, or a non-positive quantity | No — DLT at once | Structurally wrong; a zero or negative release is either a no-op or a covert reservation increase |
| Product with no inventory row | No — DLT at once | Retrying cannot conjure the row |
| Release larger than what is reserved | No — DLT at once | Refusing keeps `reserved_quantity` honest instead of driving it negative |

Retrying a record that can never succeed is not merely wasteful here: **compensation queued behind
a poison record is stock that stays held.** That is why the non-retryable classification is
aggressive, and why a poison record is proven not to stall the records behind it.

**A dead-lettered compensation means stock stays reserved.** It is visible on the DLT topic and in
`inventory_compensation_failed_total`, and it needs an operator. There is no inspection/replay UI
for this topic yet — ADR 0003's tooling is order-service-local and payment-shaped. That is
deliberate scope, recorded as future work.

## Tracing

The trace is **not** continuous across the outbox, and is not made to look continuous.

```
[trace A]  payment consumer span -> order-service DB transaction (cancel + outbox row)
                                      … durable gap, possibly minutes …
[trace B]  outbox publisher -> Kafka producer span -> inventory consumer span
```

Trace context is not stored in the outbox row, so the publisher starts a new trace. Faking a single
continuous trace across durable storage would misrepresent when the work actually happened. This
matches the payment outbox exactly. Correlate the two halves by `orderId` and `eventId`, both of
which are on every log line.

Within trace B, W3C `traceparent` propagation is intact: the producer stamps it and the consumer
continues it.

## Observability

Structured logs, no full payloads:

| Event | Level | Logger |
| --- | --- | --- |
| Compensation queued in the outbox | INFO | `PaymentEventProcessor` |
| Compensation publish succeeded | INFO | `OrderOutboxBatchProcessor` |
| Compensation publish failed, retained for retry | WARN | `OrderOutboxBatchProcessor` |
| Compensation released (one line per product) | INFO | `InventoryReleaseProcessor` |
| Compensation applied | INFO | `InventoryReleaseProcessor` |
| Duplicate compensation ignored | INFO | `InventoryReleaseProcessor` |
| Compensation dead-lettered | ERROR | `KafkaConsumerConfig` (inventory-service) |

Each line carries `eventId` and `orderId`; per-product release lines also carry `productId` and
`quantity`. Reservation ids do not appear because none exist.

Metrics (fixed cardinality, one `result` tag at most):

| Metric | Meaning |
| --- | --- |
| `order_compensation_persisted_total` | Compensation rows written inside a business transaction |
| `order_compensation_publish_total{result="success"}` | Acknowledged publishes |
| `order_compensation_publish_total{result="failure"}` | Publish attempts the broker did not acknowledge |
| `inventory_compensation_received_total` | Compensation events received |
| `inventory_compensation_released_total` | Events that actually released stock |
| `inventory_compensation_duplicate_ignored_total` | Redeliveries ignored as already applied |
| `inventory_compensation_failed_total` | Events that could not be applied (retried or dead-lettered) |

A healthy system has `released + duplicate_ignored ≈ received`, and
`order_compensation_persisted_total` tracking `order_compensation_publish_total{result="success"}`
with only a short lag.

## Configuration

| Property | Default | Meaning |
| --- | --- | --- |
| `order.outbox.enabled` | `true` | Set `false` to hold the publisher still; rows stay durable |
| `order.outbox.poll-interval` | `1s` | How often to look for pending rows |
| `order.outbox.batch-size` | `20` | Rows claimed per cycle |
| `order.outbox.send-timeout` | `15s` | How long to wait for the broker per row |
| `order.outbox.initial-backoff` / `max-backoff` | `1s` / `5m` | Exponential retry bounds |
| `spring.kafka.template.observation-enabled` (order) | `true` | Producer span + `traceparent` on compensation publishes |
| `spring.kafka.producer.properties.max.block.ms` / `delivery.timeout.ms` (order) | `5000` / `10000` | Bound a send while the broker is down, as payment-service does |
| `spring.kafka.consumer.group-id` (inventory) | `inventory-service` | Compensation consumer group |
| `spring.kafka.listener.auto-startup` (inventory) | `true` | Tests set `false` to keep the consumer parked |

The topic names are contract constants in `shared/common-events` (`KafkaTopics.ORDER_COMPENSATION_V1`
and its `.DLT`), not per-service configuration, matching `payment.events.v1`. Everything else is
environment-overridable in the usual pattern (`ORDER_OUTBOX_*`, `KAFKA_BOOTSTRAP_SERVERS`).

## Known limitations

- **No reservation records.** Compensation restates the order's line items. A real reservation
  model is the natural next step; see ADR 0004.
- **A dead-lettered compensation leaves stock held** until an operator intervenes, and there is no
  inspection UI for `order.compensation.v1.DLT` yet.
- **`order_outbox_event` grows without bound** — published rows are never pruned.
- **No exactly-once delivery**, by design and by honest accounting.
- **No generic saga orchestrator.** This is one choreographed compensating event for one failure
  path, not a workflow engine.
- **Compensation is not attributed to a user**, because it is triggered by an event rather than a
  person.
- **Only the `PaymentFailed` path compensates.** A manual `POST /api/v1/orders/{id}/cancel` of a
  checked-out order releases nothing (it is also what checkout calls after releasing synchronously,
  which is why it must stay silent). Distinguishing the two needs a real reservation model.
- **Checkout's own synchronous compensation is best-effort.** If checkout releases the reservation
  but its follow-up order cancel fails, the order stays `PENDING`; a later `PaymentFailed` would then
  request a second release. Inventory refuses it only if it exceeds what is currently reserved — if
  other orders hold enough units of that product, it would release theirs. A reservation model keyed
  by order closes this; checkout was intentionally left unchanged here.
- **A confirmed order's reservation is never consumed.** `CONFIRMED` leaves `reserved_quantity` as
  it was (unchanged behaviour); there is no `CONSUMED` transition until fulfilment exists.
