# Dead-letter operations: inspection and replay

Operator guide for `payment.events.v1.DLT`. It covers why a record lands there, how to look at
it, what replay does and — importantly — what replay does **not** do.

See also: [payment-events.md](payment-events.md) for the event contract, and
[../decisions/0003-dlt-inspection-and-replay.md](../decisions/0003-dlt-inspection-and-replay.md)
for why this is built the way it is.

## Why events enter the dead-letter topic

order-service consumes `payment.events.v1` with a bounded retry policy: initial delivery plus 2
retries, then the record is published to `payment.events.v1.DLT` with its key and value intact.
Two different routes lead there.

| Route | Cause | Retried first? |
| --- | --- | --- |
| `NonRetryableEventException` | Malformed JSON; unknown `eventType`; unsupported `schemaVersion`; payload missing `orderId`; unknown order; a transition that contradicts the order's current status (for example authorizing an order that is already `CANCELLED`) | No — dead-lettered immediately, because it can never succeed |
| Any other exception | A transient failure such as a database outage | Yes — 3 attempts total, then dead-lettered |

A dead-lettered record leaves **no** `processed_event` marker and never moves the order.

## How dead-letter records are inspected

A dedicated listener (`order-service-dlt` consumer group, separate from the business group)
consumes the DLT topic and writes one durable row per record into `dead_letter_event`. It never
calls the payment-event processor, so capturing a dead letter cannot cause an order transition.

Ingestion is duplicate-safe and restart-safe: the row is keyed by the physical record coordinate
`(dlt_topic, dlt_partition, dlt_offset)`, so a redelivery after an uncommitted offset is a no-op
rather than a second row.

A record whose payload cannot be parsed is **still stored**. The envelope columns (`event_id`,
`event_type`, `schema_version`, `order_id`) are simply null, while the Kafka-level facts —
coordinates, key, exception, consumer group, `traceparent`, and the raw value — are all present.
That is deliberate: unparseable records are exactly the ones an operator most needs to see.

`exception_class` records the *cause* where the recoverer captured one, falling back to the
wrapper (`ListenerExecutionFailedException`). It is stored and displayed as plain text and is
never resolved to a Java type — no class name arriving over Kafka influences behaviour.

### Endpoints

```
GET  /api/v1/admin/dlt/payment-events
GET  /api/v1/admin/dlt/payment-events/{id}
POST /api/v1/admin/dlt/payment-events/{id}/replay
```

Listing filters, all optional and combinable: `eventId`, `eventType`, `orderId`, `partition`,
`status`. Paging is `page` (default 0) and `size` (default 20, **maximum 100**); an out-of-range
size is a `400`, and there is no unbounded query path. Results are newest first.

The list response omits the raw payload; only the detail endpoint returns it. Payloads are never
written to the application logs.

## What replay actually does

`POST .../{id}/replay` reads the **stored** original value and key and republishes them to the
record's own stored original topic, then waits for the broker to acknowledge.

- The payload is republished **byte-for-byte**. It is never rewritten, reformatted or upgraded.
- The `eventId` inside it is **preserved**. Replay never mints a new one.
- The record is marked `REPLAYED` **only after** Kafka acknowledges the send. Until then it is
  not considered replayed.
- The caller names a stored record and nothing else. There is no request field for a topic or a
  payload, and the stored `original_topic` is additionally checked against an allow-list
  (currently just `payment.events.v1`), so this endpoint cannot be turned into a general-purpose
  producer.
- A stored payload the consumer provably cannot read is rejected with `400` *before* anything is
  published.

### Status model

| Status | Meaning |
| --- | --- |
| `NEW` | Captured, never replayed |
| `REPLAYED` | Republished and acknowledged by the broker |
| `REPLAY_FAILED` | A replay attempt was not acknowledged. The stored payload is untouched and the record can be replayed again |

`replay_count` counts replay **attempts**, including failed ones. `replayed_at` is set only on
success. `last_replay_error` holds the most recent failure and is cleared by a later success.

## Replay and deduplication

Replay does not bypass, disable or interact with consumer deduplication — and must not.

order-service records every successfully applied `eventId` in `processed_event` inside the same
transaction as the order transition. If a replayed event's `eventId` is already in that table,
the consumer recognises it as a duplicate and ignores it. **This is correct.** The event was
already applied; applying it twice would be the bug.

The practical consequence: replaying an event that previously succeeded changes nothing. Replay
is useful for events that never applied — the ones that were dead-lettered.

## Replay is not a fix

**Replay republishes an event. It does not repair the condition that rejected it.**

If the reason the event was dead-lettered still holds when it is redelivered, it will be
dead-lettered **again**, producing a second `dead_letter_event` row with the same `eventId` and a
new DLT coordinate. This is expected behaviour, not a failure of the tooling, and it is covered
by an integration test.

The common case: `PaymentAuthorized` arrives for an order that a `PaymentFailed` already
cancelled. `CANCELLED → CONFIRMED` is not a legal order transition, so the event is rejected.
Replaying it changes nothing, because the order is still cancelled.

### Operator responsibility before replaying

Replay is worth attempting when the failure was **environmental** — the database was down, a
dependency was unreachable — and that condition has since been fixed.

Before replaying, check:

1. **Why did it fail?** Read `exception_class` and `exception_message` on the record.
2. **Is that cause gone?** A transient infrastructure failure may be; a semantic contradiction is
   not, and no amount of replaying will change it.
3. **What is the order's current state?** Fetch `GET /api/v1/orders/{orderId}` using the
   record's `order_id`. If the current state makes the event impossible, replay will just
   re-dead-letter it.
4. **Was it already applied?** If the `eventId` is in `processed_event`, dedup will ignore it.

A semantic inconsistency is a business problem — it needs a decision about the order, not a
redelivery.

## Delivery semantics

Nothing here creates exactly-once delivery. The system remains **at-least-once plus consumer-side
deduplication**, and replay is an additional, deliberate at-least-once redelivery. Idempotency
lives in `processed_event`, exactly as it did before this tooling existed.

## Security

**These endpoints are not authorization-protected, and that is a known gap.**

This repository has no cross-service authorization model yet: auth-service issues JWTs, but no
downstream service validates roles from them. Inventing a role check for this controller alone
would be a fake boundary that looks like security without being it. Instead the routes live under
the `/api/v1/admin/**` prefix, which an edge policy can match on as a single rule.

**Before production, all of the following are required:**

- Authentication and an admin-role check on `/api/v1/admin/**`, at the gateway or as a resource
  server filter in order-service.
- Network-level restriction so admin routes are not publicly routable.
- An audit trail for who replayed what. Replays are currently logged (record id, `eventId`,
  `eventType`, topic, coordinates, replay count) but not attributed to a principal, because there
  is no authenticated principal to attribute them to.

Until then, treat replay as an operation available to anyone who can reach the service port.

## Observability

Structured logs (no payloads):

| Event | Level | Logger |
| --- | --- | --- |
| DLT record captured | WARN | `DeadLetterEventListener` |
| Duplicate DLT record ignored | DEBUG | `DeadLetterEventListener` |
| Replay requested | INFO | `DeadLetterAdminService` |
| Replay succeeded | INFO | `DeadLetterAdminService` |
| Replay failed | ERROR | `DeadLetterAdminService` |
| DLT record could not be captured after retries | ERROR | `KafkaConsumerConfig` |

Each line carries the record id and, where known, `eventId`, `eventType`, `orderId`, the original
topic and the Kafka coordinates.

Metrics, on the existing Prometheus registry:

| Metric | Meaning |
| --- | --- |
| `order_dlt_captured_total` | Records captured into inspection storage |
| `order_dlt_duplicate_ignored_total` | Redeliveries ignored as already captured |
| `order_dlt_replay_total{result="success"}` | Acknowledged replays |
| `order_dlt_replay_total{result="failure"}` | Replay attempts the broker did not acknowledge |

Cardinality is fixed: one low-cardinality tag (`result`), and nothing derived from event ids,
order ids, topics or exception text.

## Known limitations

- **Ingestion failures can skip a record.** If the database is unavailable, capture is retried 3
  times and then logged at ERROR and skipped so the DLT partition is not stalled forever. The
  record remains on the Kafka topic (subject to its retention) but will have no inspection row.
- **No retention or archival.** `dead_letter_event` grows without bound, including the stored
  payloads.
- **No bulk replay.** Replay is one record at a time, on purpose.
- **Replay is not attributed to a user.** See Security above.
- **A replayed record that fails again produces a new row,** so one problematic event can
  accumulate several rows. They are distinguishable by their DLT coordinates.
