# 1. Asynchronous payment-to-order events over Kafka

- **Status:** Accepted
- **Date:** 2026-09-07
- **Scope:** `payment-service`, `order-service`, `shared/common-events`, `infrastructure/kafka`

## Context

The platform was entirely synchronous HTTP. `checkout-service` orchestrates cart, product,
inventory, order and payment in one request and leaves the order and the payment both `PENDING`;
nothing then moved the order forward. Advancing an order's lifecycle when its payment resolves
was the smallest useful piece of asynchronous messaging the system actually needed.

## Decision

Introduce one Kafka topic, `payment.events.v1`. payment-service publishes `PaymentAuthorized`
and `PaymentFailed`; order-service consumes them and transitions the order to `CONFIRMED` or
`CANCELLED`. The wire contract lives in a shared `common-events` module. Kafka is opt-in
locally. Details of the topic, the envelope and the consumer semantics are in
[../events/payment-events.md](../events/payment-events.md).

## Rationale

**Why payment outcome -> order lifecycle first.** It is a genuine cross-service state
transition with a real ordering requirement (per order), a real duplicate-delivery problem, and
a real "these two services disagree" failure mode. It exercises everything asynchronous
messaging is for without inventing a new business flow, and both endpoints of it already
existed. It is also the one place where the synchronous design left an obvious hole: an order
that nothing could ever confirm.

**Why checkout stays synchronous.** Checkout's caller needs an immediate answer - the order id,
the payment id, and whether the inventory reservation succeeded - and its compensations
(release the reservation, cancel the order) depend on that immediate result. Making it
event-driven would mean designing a Saga, which is a much larger change with no benefit to the
current flow. Checkout was not modified by this milestone at all.

**Why JSON.** The payload is a handful of scalar fields. JSON is inspectable with
`kafka-console-consumer` and needs no registry, no code generation and no build-time schema
step - all of which would be real infrastructure to run and maintain locally. Avro or Protobuf
with a Schema Registry buys compatibility enforcement that a two-service, one-version contract
does not yet need. The `schemaVersion` field and the versioned topic name keep that door open.

**Why one `payment.events.v1` topic.** Both event types describe the same aggregate's lifecycle
and are consumed by the same consumer, in order, per order. Splitting them into
`payment.authorized.v1` and `payment.failed.v1` would destroy the per-order ordering guarantee
that the single keyed topic provides - a `PaymentFailed` could overtake a `PaymentAuthorized`
for the same order across two topics. The version is in the topic name so an incompatible v2
contract can run alongside v1 rather than breaking consumers.

**Why `common-events` exists.** The envelope and payload records are the contract, and having
one definition prevents the producer and consumer drifting apart silently. It deliberately
contains only records and constants - no Spring, no Kafka, no service code - so depending on it
does not couple the services to each other's internals. Note that the shared *module* is not the
same as putting a Java class name on the wire: nothing about the Java types is serialized, so a
service could stop using the module entirely and still speak the protocol.

**Why order-service uses `processed_event`.** Kafka delivery is at-least-once and the offset
commit is not atomic with the database transaction, so redelivery is expected, not exceptional.
Writing a marker row keyed by `eventId` in the same transaction as the order transition makes
reprocessing safe using the database's own primary key - a mechanism that already has to be
correct - rather than an in-memory cache that a restart would empty or a Kafka feature that
would only cover the Kafka half of the work.

**Why a DLT exists.** Some records can never succeed: malformed JSON, an unknown order, or a
`PaymentAuthorized` for an order that is already `CANCELLED`. Without a dead-letter topic the
only options are to retry forever, blocking the partition, or to drop the record and lose the
evidence. The DLT bounds the retry and preserves the record - key, value and diagnostic headers -
for inspection.

**Why Kafka is opt-in locally.** The stack already runs 9 JVMs, 8 Postgres instances and the
observability components on a developer machine; a broker on top of that is a meaningful cost
for anyone working on an unrelated service. Keeping the broker in a separate Compose overlay
means the base stack and its E2E suite are unchanged and still pass with no broker at all, which
also continuously proves that the asynchronous path is genuinely additive.

## Consequences

Accepted, and deliberately not worked around:

- **The database commit and the Kafka send are NOT atomic.** payment-service publishes from an
  `AFTER_COMMIT` transaction listener. A crash or broker outage in the window between the commit
  and the send loses the event: the payment is durably `AUTHORIZED` while the order stays
  `PENDING` forever. The failure is logged and never rolled back into the payment.
- **A Transactional Outbox is deferred.** It is what closes the gap above, by writing the event
  to the payment database in the same transaction and relaying it afterwards.
- **This is NOT exactly-once delivery.** It is at-least-once delivery plus consumer-side
  deduplication through `processed_event`. Effects are idempotent; deliveries are not unique.
- **Saga orchestration is deferred.** Checkout's compensations remain synchronous and in-process.
- **Inventory reservation ownership is deferred.** When a `PaymentFailed` event cancels an
  order, nothing releases the inventory that checkout reserved. No `OrderCancelled -> inventory
  release` flow is introduced here; deciding who owns that release is part of the Saga work.
- **The local broker is a single node with replication factor 1.** Not a highly-available
  configuration, and no delivery guarantee should be inferred from it.
- Two services now share a build dependency on `common-events`, so a breaking change to the v1
  records is a breaking change to both. The `schemaVersion` field and the versioned topic name
  are the intended escape hatch.

## Alternatives considered

- **Synchronous callback from payment to order.** Rejected: it recreates the coupling and the
  distributed-transaction problem that the payment/order split exists to avoid, and it gives
  payment-service a hard runtime dependency on order-service being up.
- **Polling order-service for resolved payments.** Rejected: latency and load scale with the
  number of pending orders, and it puts payment lifecycle knowledge in order-service.
- **Kafka transactions / exactly-once semantics.** Rejected for now: the read-process-write
  guarantee still would not cover the JPA write, so consumer-side deduplication would be needed
  anyway. Idempotent consumption is the simpler mechanism that actually covers both halves.
