# Payment events

The one asynchronous flow on the platform: a payment outcome drives its order's lifecycle.
Everything else, including checkout itself, is still synchronous HTTP.

```
payment-service  --PaymentAuthorized/PaymentFailed-->  payment.events.v1  -->  order-service
                                                                                    |
                                                          (semantic inconsistency)  v
                                                                       payment.events.v1.DLT
```

## Topics

| | |
|---|---|
| Topic | `payment.events.v1` |
| Dead-letter topic | `payment.events.v1.DLT` |
| Record key | `orderId` (UTF-8 string) |
| Partitions | 3 (local development) |
| Replication factor | 1 (local development, single broker) |
| Key/value serialization | `StringSerializer` / `StringDeserializer` |
| Consumer group | `order-service` |

Both topics are declared as `NewTopic` beans and created by each service's `KafkaAdmin` on
startup - `payment.events.v1` by payment-service (its publisher), `payment.events.v1.DLT` by
order-service (which owns the dead-letter stream for what it consumes). The broker runs with
`auto.create.topics.enable=false`, so a topic that no service declares simply does not exist.

Production sizing would raise both the partition count and the replication factor. Three
partitions and RF 1 are a local-development choice, not a recommendation.

## Envelope

Every record value is a JSON object in this shared v1 envelope
(`shared/common-events`, `com.sumedha.commerce.common.events.EventEnvelope`):

| Field | Type | Notes |
|---|---|---|
| `eventId` | UUID | Unique per event. The consumer's deduplication key. |
| `eventType` | string | `PaymentAuthorized` or `PaymentFailed`. Selects the payload type. |
| `schemaVersion` | int | Always `1`. A consumer rejects anything else. |
| `occurredAt` | ISO-8601 instant | When the event was raised. |
| `payload` | object | Shape determined by `eventType`. |

### `PaymentAuthorized` payload

| Field | Type |
|---|---|
| `paymentId` | UUID |
| `orderId` | UUID |
| `userId` | UUID |
| `amount` | decimal |
| `currency` | 3-letter string |

### `PaymentFailed` payload

| Field | Type |
|---|---|
| `paymentId` | UUID |
| `orderId` | UUID |
| `userId` | UUID |
| `failureReason` | string (the sanitized value persisted on the entity, not the raw request) |

### Example

```json
{
  "eventId": "f7d49f04-f90e-4204-a6b2-d4c59236efce",
  "eventType": "PaymentAuthorized",
  "schemaVersion": 1,
  "occurredAt": "2026-09-07T18:52:22.196557214Z",
  "payload": {
    "paymentId": "fe69d951-bbca-407e-b2d4-2b78c69a1952",
    "orderId": "240e7be2-3d7c-49c9-9838-ec95ec4678aa",
    "userId": "731b6df9-7aa3-4715-b298-13bc7a0b59b6",
    "amount": 50.00,
    "currency": "USD"
  }
}
```

### No Java type on the wire

The value is serialized as a plain JSON string by the producer and consumed as a plain
`String` by the consumer, which parses it and binds the payload to an explicitly named record
chosen by the `eventType` string. There is no `__TypeId__` header, no Jackson default typing,
and no Java fully-qualified class name anywhere in the record. The contract is the JSON schema
above and nothing else, so the two services can be refactored, renamed or reimplemented
independently. It also means a malformed record is an ordinary application error rather than a
deserializer failure that would stall the partition.

## Ordering

Records are keyed by `orderId`, so all events for one order land on the same partition and are
delivered in publication order. **That is the only ordering guarantee.** There is no ordering
between different orders, and the listener runs with `concurrency: 3` (one consumer per
partition), so events for different orders are processed in parallel.

Publication order itself is preserved per aggregate by the outbox. A row is claimed only when no
earlier unpublished row exists for the same `aggregate_id`, ordered by `(created_at, id)`. So a
payment that goes `AUTHORIZED` then `FAILED` cannot publish `PaymentFailed` while its
`PaymentAuthorized` row is still retrying - which would otherwise dead-letter the late
`PaymentAuthorized` against an order the consumer had already cancelled. The guard is scoped to
one aggregate: an order stuck in backoff never holds back a different order, and a single batch
therefore claims at most one row per aggregate.

## Delivery and deduplication

Delivery is **at-least-once**. payment-service writes the payment transition and the complete
serialized envelope to `payment_outbox_event` in one PostgreSQL transaction. A scheduled
publisher claims bounded batches with `FOR UPDATE SKIP LOCKED`, waits for Kafka acknowledgement,
and only then marks each row `PUBLISHED`. A failed send leaves the row `PENDING`, increments its
attempt count, records the error, and schedules a bounded exponential-backoff retry.

Kafka acknowledgement and the later outbox status commit are not atomic. If payment-service
crashes between them, the same stored envelope is published again. Its persisted `eventId` is
never regenerated. The consumer can also redeliver after a rebalance or a crash between its
database commit and offset commit.

Redelivery is made safe by the `processed_event` table, not by any transactional-messaging
mechanism:

| Column | Notes |
|---|---|
| `event_id` | UUID primary key - the deduplication key |
| `event_type` | The `eventType` that was applied |
| `order_id` | Indexed; not foreign-keyed to `orders` (consumer infrastructure, not part of the aggregate) |
| `processed_at` | When the marker was written |

The marker is written in the **same transaction** as the order transition. A failure anywhere
rolls back both, so a marker is never left behind for an event that was not applied, and a
second delivery of the same `eventId` finds the marker and returns without touching the order.

## How the consumer classifies an event

| Situation | Outcome |
|---|---|
| `eventId` already in `processed_event` | `DUPLICATE` - no-op success, offset advances |
| `PaymentAuthorized`, order `PENDING` | `APPLIED` - order becomes `CONFIRMED` |
| `PaymentFailed`, order `PENDING` or `CONFIRMED` | `APPLIED` - order becomes `CANCELLED` (`CONFIRMED -> CANCELLED` is permitted) |
| `PaymentAuthorized`, order already `CONFIRMED` | `ALREADY_IN_TARGET_STATE` - idempotent no-op, marker still written for the new `eventId` |
| `PaymentFailed`, order already `CANCELLED` | `ALREADY_IN_TARGET_STATE` - idempotent no-op, marker still written |
| `PaymentAuthorized`, order `CANCELLED` | **Semantic inconsistency** - dead-lettered, no marker written |
| Unknown `orderId`, unparseable JSON, unknown `eventType`, unsupported `schemaVersion` | Dead-lettered, no marker written |

The distinction in the last two rows matters: an authorization arriving for an order that is
already cancelled is not a duplicate and is not silently swallowed. It means payment and order
disagree about reality, so the record is preserved on the dead-letter topic for a human to look
at rather than being dropped.

## Retry and dead-lettering

The container's `DefaultErrorHandler` retries a failing record twice with a 1s fixed backoff
(3 attempts total), then publishes it to `payment.events.v1.DLT`. Retries are always bounded -
a record can never loop forever.

`NonRetryableEventException` - everything in the last two rows of the table above - is
registered as non-retryable, so those records skip the retry budget and are dead-lettered on
the first attempt.

The dead-letter record preserves the original key and the original value bytes, and Spring Kafka
adds diagnostic headers: `kafka_dlt-original-topic`, `-original-partition`, `-original-offset`,
`-original-timestamp`, `-original-timestamp-type`, `-original-consumer-group`,
`-exception-fqcn`, `-exception-cause-fqcn`, `-exception-message` and `-exception-stacktrace`.
The `traceparent` header survives too, so a dead-lettered record can be traced back to the HTTP
request that produced it.

**There is no replay tooling.** Nothing consumes `payment.events.v1.DLT`, and there is no
command or endpoint to re-inject a dead-lettered record into the main topic. Inspecting and
replaying is a manual `kafka-console-consumer` / `kafka-console-producer` exercise today.

## Tracing

`KafkaTemplate` observation still creates producer observations and injects a W3C `traceparent`
header, which the listener continues. Publishing is now asynchronous to the payment HTTP
request, however, and trace context is not persisted in the outbox. The producer/consumer trace
therefore does not pretend to be a continuation of the completed HTTP trace. Durable trace
continuation is deferred until it can be implemented with supported Micrometer/OpenTelemetry
context propagation rather than hand-built trace headers. See
[../architecture/observability.md](../architecture/observability.md).

## Known limitations

- **Kafka acknowledgement and the outbox status update are not atomic.** A crash after the
  broker acknowledges but before `PUBLISHED` commits causes a duplicate publish with the same
  `eventId`. Consumer deduplication makes this safe; it is not exactly-once delivery.
- **This is not exactly-once delivery.** It is at-least-once plus consumer-side deduplication.
  Per-aggregate publication ordering does not change that: a row can still be published twice.
- **Published outbox rows are never cleaned up.** `payment_outbox_event` and its indexes grow
  without bound; retention or archival is a future concern.
- **Retry timing assumes roughly aligned clocks.** Eligibility is evaluated with the database's
  `now()` while `next_attempt_at` is written from the publishing instance's clock, so significant
  skew shifts retry timing (it does not affect correctness or ordering).
- **`attempt_count` counts failed publish attempts,** not total attempts: a row published on its
  first try stays at `0`.
- **No DLT replay tooling** (above).
- **Nothing releases the inventory reservation** when an order is cancelled by a
  `PaymentFailed` event. The reservation checkout made still stands. Ownership of that release
  is deferred.
- **Single broker, RF 1** locally. Not a highly-available configuration; a broker loss loses data.

## Running it locally

Kafka is opt-in - the base stack does not start a broker, and neither service is wired to one
without the overlay:

```bash
docker compose \
  -f tests/end-to-end/compose.yml \
  -f infrastructure/kafka/compose.kafka.yml \
  up -d kafka payment order
```

Host tooling connects on `localhost:29092`; containers use `kafka:9092`.

```bash
# topics
docker exec end-to-end-kafka-1 /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic payment.events.v1

# tail the stream
docker exec end-to-end-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic payment.events.v1 \
  --from-beginning --property print.key=true

# consumer lag
docker exec end-to-end-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group order-service
```
