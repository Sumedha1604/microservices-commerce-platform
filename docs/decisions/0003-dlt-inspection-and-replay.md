# 3. Database-backed dead-letter inspection and replay

- **Status:** Accepted
- **Date:** 2026-09-08
- **Scope:** `order-service`

## Context

ADR 0001 gave order-service bounded retry and a dead-letter topic. That stops a poison record
from stalling a partition, but it leaves the record somewhere an operator cannot practically
look: answering "what is on the DLT, and why" meant running `kafka-console-consumer` by hand,
and there was no way to put a record back after fixing its cause.

The DLT is also the one place where the failures nobody anticipated end up, so the tooling has to
stay useful precisely when the payload is garbage.

## Decision

**Persist, do not scan.** A dedicated listener consumes `payment.events.v1.DLT` and writes one
row per record into `dead_letter_event`. Inspection is then an ordinary indexed, paged SQL query
rather than a Kafka scan on every HTTP request. This also outlives topic retention and gives
replay somewhere to record its outcome.

Identity is the physical record coordinate `(dlt_topic, dlt_partition, dlt_offset)`, unique in
the schema. That is what makes ingestion restart-safe without any coordination: offsets commit
after the row commits, so a crash redelivers, and the constraint turns the redelivery into a
no-op. `eventId` is deliberately *not* unique — the same event can be dead-lettered repeatedly,
and a malformed record has no `eventId` at all.

**Parsing never fails ingestion.** Envelope fields are extracted best-effort from a loose JSON
tree and are all nullable. A record that cannot be parsed still produces a row carrying its raw
value, coordinates, key, exception and trace context.

**A separate consumer group and container factory.** The DLT listener does not share the business
group, and its container factory has no dead-letter recoverer — republishing a capture failure to
the dead-letter topic would be a self-feeding loop. After bounded retries a record is logged at
ERROR and skipped so the partition cannot stall.

**Replay is narrow by construction.** The caller names a stored record id. The topic comes from
the stored row and is checked against an allow-list; the payload and key are the stored ones,
republished byte-for-byte. The stored payload is validated with the same parser the consumer uses
before anything is published, and the row is marked `REPLAYED` only after the broker acknowledges.

**Deduplication is untouched.** Replay preserves the `eventId`, so `processed_event` continues to
decide whether an event applies. That is the whole point: replay is a redelivery mechanism, not
an override.

## Alternatives considered

- **Bounded on-demand Kafka consumer.** No schema, no migration. Rejected: every request pays a
  seek/scan, filtering by `eventId` or `orderId` means reading the topic, there is nowhere to
  record replay outcomes, and records vanish at topic retention.
- **Spring Kafka's `@RetryableTopic` / `DltHandler`.** Would have replaced the existing explicit
  error-handler topology for less control over what is stored.
- **A separate admin microservice.** Rejected as unjustified: the data, the parser and the
  consumer semantics all live in order-service, and a new service would duplicate the contract
  layer to gain nothing.

## Consequences

- Dead-letter records are queryable by `eventId`, `eventType`, `orderId`, `partition` and status,
  with bounded pages and no unbounded query path.
- Malformed records stay inspectable, which is the case that matters most.
- Replay preserves the `eventId` and the exact payload, so consumer deduplication stays
  authoritative and no new event identity is invented.
- **Replay does not fix anything.** If the rejecting condition still holds, the replayed record is
  dead-lettered again and captured as a second row. This is expected and tested.
- Delivery remains at-least-once plus consumer-side deduplication. Replay is one more deliberate
  redelivery; it makes no exactly-once claim.
- `dead_letter_event` stores full payloads and has no retention policy, so it grows without bound.
- If the database is unavailable, ingestion retries and then skips the record so the DLT partition
  is not stalled; that record will have no inspection row.
- **The admin endpoints are unauthenticated.** The repository has no cross-service authorization
  model to hook into, so rather than invent one they are namespaced under `/api/v1/admin/**` for
  an edge policy to match. Production hardening is required and is documented in
  `docs/events/dlt-operations.md`.
- payment-service is unchanged; this milestone is entirely consumer-side.
