# 4. Choreographed inventory compensation on payment failure

- **Status:** Accepted
- **Date:** 2026-09-10
- **Scope:** `order-service`, `inventory-service`, `shared/common-events`

## Context

Checkout reserves inventory synchronously, then creates the order and the payment. If either of
those calls fails, checkout releases the reservation itself, in the same request. That part works.

The hole is everything after checkout returns `201`. Payment authorization is asynchronous: it
completes later and publishes `PaymentAuthorized` or `PaymentFailed` through payment-service's
outbox. order-service consumes those and cancels the order on failure — and **nothing released the
inventory**. The reserved units stayed held forever, invisible to the customer and to anyone
looking at available stock.

One fact shaped every decision below: **this platform has no reservation records.** A reservation
is not a row — it is a counter, `inventory.reserved_quantity`, incremented by
`POST /api/v1/inventory/{inventoryId}/reserve`. That endpoint takes a quantity and nothing else.
No reservation id is minted, no order id is stored, and checkout's in-memory list of what it
reserved dies with the HTTP request. The only durable record of what was reserved for an order is
that order's `order_items`.

## Decision

**order-service emits `InventoryReleaseRequested`; inventory-service consumes it.** The event is
published on `order.compensation.v1`, keyed by `orderId`.

**The event carries the lines, because there is no reservation id to carry.** The payload is
`{orderId, reason, lines: [{productId, quantity}]}`. `productId` is what the two services
genuinely share: order-service has it in `order_items`, and inventory-service can resolve it to
its own row because `product_id` is unique there. Inventing a reservation id now would have meant
a new table, a checkout change to populate it, and a migration of existing held stock — for no gain
over the line items we already store.

**An intent, not a lifecycle announcement.** `OrderCancelled` was the alternative and was
rejected. Two reasons:

- It would make inventory-service infer "release stock" from "an order changed state", putting
  order semantics inside inventory. Every future consumer of `OrderCancelled` would then have to
  know that one of its subscribers treats it as a stock command.
- It would be the *wrong* fact to act on. Orders are cancelled through paths that already released
  their inventory — checkout's own failure handler calls `POST /orders/{id}/cancel` immediately
  after releasing the reservations synchronously. A consumer keyed on "cancelled" would release
  that stock a second time.

So compensation is emitted **only from the `PaymentFailed` consumer path**, and only on a real
`PENDING|CONFIRMED -> CANCELLED` transition. It is deliberately *not* attached to `Order.cancel()`,
which is what the HTTP cancel endpoint calls.

**inventory-service never consumes payment events.** "A payment failed" is not an inventory
concern; inventory reacts to a request about inventory.

**A transactional outbox in order-service.** The cancellation and the compensation row commit in
one PostgreSQL transaction; a background publisher sends the row and marks it `PUBLISHED` only
after the broker acknowledges. No Kafka call happens inside the business transaction. The table,
the per-aggregate ordering guard and the backoff mirror `payment_outbox_event` — the reliability
problem is identical, and two services solving it differently would be worse than the duplication.

**Deduplication by `eventId` in a `processed_event` table in inventory-service.** With no
reservation rows there is no reservation status to guard a replay with, so the event id is the only
thing that can make a release idempotent. The marker is claimed with
`insert … on conflict (event_id) do nothing` **before any stock moves**, in the same transaction as
the release. A second worker racing the same event waits on that insert, then sees 0 rows and
returns a duplicate outcome having touched nothing. Duplicates are therefore an outcome, never an
exception, so no integrity error is ever swallowed on the assumption that it was a duplicate.
Inventory rows are then locked `FOR UPDATE` in ascending `productId` order, so the reserved-quantity
check and the decrement are not separable and concurrent releases cannot deadlock.

**Releases that the stock cannot support are refused, not retried.** An unknown product, or more
units than are actually reserved, is a `NonRetryableEventException` and goes straight to
`order.compensation.v1.DLT`. Retrying could never make it true, and the alternative to refusing is
driving `reserved_quantity` negative against its check constraint.

## Alternatives considered

- **A saga orchestrator service.** Rejected as unjustified for one compensation step. There is
  exactly one failure path here; a coordinator would add a deployment, a state machine and a new
  source of truth to express what one event already expresses.
- **A workflow engine (Temporal, Camunda) or Kafka Streams.** Rejected: far more machinery than a
  single compensating event needs, and each is a platform commitment of its own.
- **Synchronous release from order-service.** order-service would call inventory-service over HTTP
  while handling `PaymentFailed`. Rejected: it couples the two services at runtime, and a release
  lost to a network failure is lost for good — exactly the durability problem the outbox exists to
  solve.
- **Introducing a real reservation table.** Rejected *for this milestone*, not on principle. It is
  the right long-term model (see Future work), but it needs a checkout change, a migration, and a
  story for stock already held — none of which this milestone requires to close the leak.
- **Making inventory-service consume `payment.events.v1` directly.** Rejected on ownership: it
  would give inventory a dependency on the payment contract and on order-state semantics it has no
  business knowing.

## Consequences

- Payment failure now returns reserved stock, asynchronously and durably. If Kafka is down the
  order stays cancelled and the compensation row stays `PENDING` and retryable.
- **Delivery is at-least-once plus consumer-side deduplication. This is not exactly-once, and no
  part of it claims to be.** The outbox can publish a row twice — a crash between the broker's
  acknowledgement and the `PUBLISHED` update guarantees it — and `processed_event` is what makes
  that harmless.
- **Ordering is per-order only.** The outbox claims at most one `PENDING` row per `aggregate_id`,
  so a later event for an order cannot overtake an earlier one; unrelated orders never block each
  other. Across orders there is no ordering, and none is needed.
- **The trace breaks at the outbox, honestly.** The payment consumer's span covers the DB
  transaction that writes the row. Publishing happens later, on the scheduler's thread, in a new
  trace. We do not stitch a fake continuous trace across durable storage, because trace context is
  not stored in the outbox row — the same boundary and the same reasoning as the payment outbox.
- `order_outbox_event` grows without bound; `PUBLISHED` rows are never pruned. Same limitation as
  `payment_outbox_event`.
- A compensation that is dead-lettered leaves stock held. It is visible on
  `order.compensation.v1.DLT` and in the metrics, but there is no inspection UI for that topic yet
  (see Future work).
- inventory-service gained Kafka: a consumer, a dead-letter producer, and a `processed_event`
  table. It previously had no messaging at all.
- checkout-service and payment-service are unchanged. Checkout's existing synchronous compensation
  keeps working exactly as before, and deliberately does not go through this path.

## Future work

- **A real reservation model** (`reservation` rows with `RESERVED`/`RELEASED`/`CONSUMED`, keyed by
  order) would let compensation name a reservation instead of restating line items, make partial
  fulfilment expressible, and give idempotency a second natural guard. It is the obvious next step
  and is intentionally out of scope here.
- **DLT inspection for `order.compensation.v1.DLT`.** ADR 0003's tooling is order-service-local and
  payment-shaped; reusing it here is not trivial and is explicitly not part of this milestone.
- **Pruning or archiving** published outbox rows.
