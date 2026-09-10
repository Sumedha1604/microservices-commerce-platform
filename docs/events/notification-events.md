# Notification events: recording payment outcomes

Operator guide for notification-service's consumer of `payment.events.v1`: what it consumes, what
it writes, what happens when an event arrives twice or cannot be handled, and what it deliberately
does **not** do.

See also: [payment-events.md](payment-events.md) for the upstream contract, and
[../decisions/0005-notification-event-consumer.md](../decisions/0005-notification-event-consumer.md)
for why it is built this way.

## Flow

```
payment-service outbox --PaymentAuthorized/PaymentFailed--> payment.events.v1
                                                              |            |
                                  group "order-service"  <----+            +----> group "notification-service"
                                  (confirm / cancel order)                        (one durable notification)
                                  DLT: payment.events.v1.DLT                      DLT: payment.events.v1.notification.DLT
```

The two consumers are independent. Each has its own offsets, lag and dead-letter topic, and
neither waits for the other. notification-service never calls order-service or any other
service while handling an event.

## Why payment events

Both payment events already carry every identifier a notification record needs (`eventId`,
`occurredAt`, `paymentId`, `orderId`, `userId`), plus the amount and currency or the failure
reason. Consuming them needs no change to payment-service or order-service and no synchronous
lookup. Compensation events (`order.compensation.v1`) are deliberately not consumed: they are an
internal stock-release intent, not something a customer is told.

## Topics

| | |
|---|---|
| Topic consumed | `payment.events.v1` (declared by payment-service) |
| Consumer group | `notification-service` |
| Dead-letter topic | `payment.events.v1.notification.DLT` (declared by notification-service, 3 partitions, RF 1 locally) |
| Record key | `orderId`, preserved on the dead-letter record |
| Deserialization | `StringDeserializer` for key and value; JSON parsed by the service |
| Ack mode | `RECORD`, offset committed only after the database transaction commits |
| Concurrency | 3 (one consumer per partition) |
| `auto.offset.reset` | `earliest` (see [Starting a new group](#starting-a-new-group)) |

## Supported events

| `eventType` | `notificationType` | Subject | Message |
|---|---|---|---|
| `PaymentAuthorized` | `PAYMENT_AUTHORIZED` | `Payment authorized for order {orderId}` | `Your payment of {amount} {CURRENCY} for order {orderId} was authorized (payment {paymentId}).` |
| `PaymentFailed` | `PAYMENT_FAILED` | `Payment failed for order {orderId}` | `Your payment for order {orderId} could not be completed (payment {paymentId}). Reason: {failureReason}` (the reason is omitted if absent and capped at 500 characters) |

**Naming.** Types are named for the payment fact, not an order outcome. This service never sees
whether order-service actually confirmed or cancelled the order, so the text never claims either.
The amount is bound directly from the JSON text as an exact decimal, so `50.00` stays `50.00`.

## Notification semantics

| Field | Value today | Meaning |
|---|---|---|
| `channel` | `INTERNAL` | The durable record is the delivery boundary. There is no email/SMS/push provider. |
| `status` | `CREATED` | Recorded. **Not** sent or delivered. There is no `SENT` status, because nothing is sent. |
| `userId` | from the event | The recipient, as a UUID reference only. No contact details are stored or looked up. |
| `occurredAt` | envelope `occurredAt` | When the payment event was raised. |
| `createdAt` | now | When this service recorded the notification. |

One source event produces exactly one notification today.

## Validation

The value is consumed as a raw string. It is parsed into the shared v1 `EventEnvelope`, and the
payload is bound to a record type chosen by the `eventType` **string**. No `__TypeId__` header, no
Jackson default typing, and no class name from the record is ever used; `@class`-style properties
are ignored as unknown fields.

| Check | Rejected when |
|---|---|
| Value | null, blank, not JSON, not an object |
| `eventId` | missing or not a UUID |
| `eventType` | missing, or not `PaymentAuthorized`/`PaymentFailed` (case-sensitive) |
| `schemaVersion` | anything but `1` (a missing one reads as `0`) |
| `occurredAt` | missing or not an ISO-8601 instant |
| `payload` | missing or not matching the payload record |
| `paymentId`, `orderId`, `userId` | missing or not a UUID |
| `amount` (authorized) | missing or negative |
| `currency` (authorized) | not exactly 3 letters |

`failureReason` is optional. Every rejection is a `NonRetryableEventException` and goes straight to
the DLT.

## Delivery and duplicates

Delivery is **at-least-once**. The payment outbox can republish an event with the same `eventId`
after a crash, the consumer can redeliver after a rebalance, and an operator can replay a record.
**This is not exactly-once, and nothing here claims to be.**

Duplicates are absorbed by `processed_event`, inside the same transaction as the notification:

```
BEGIN
  insert into processed_event (event_id, …) values (…) on conflict (event_id) do nothing
     -> 0 rows: DUPLICATE. Nothing else is written; the offset is committed normally.
     -> 1 row:  this transaction owns the event
  insert into notification (…)            -- flushed immediately
COMMIT                                    -- then the container commits the Kafka offset
```

- **Concurrent duplicates.** The second claim waits for the first transaction. If that commits,
  the second gets 0 and becomes a duplicate. If it rolls back, the second claims and processes the
  event. Neither outcome is an exception, so no integrity error is ever treated as "probably a
  duplicate". Proven with 8 concurrent deliveries producing exactly one notification.
- **Failure after the claim.** If the notification insert fails, the claim rolls back with it, so
  the retry (or a replay) is processed rather than swallowed.
- **Second guard.** `notification` has `UNIQUE (event_id, channel, notification_type)`, so even a
  bypassed marker cannot produce two notifications of one type for one event.
- **The `eventId` alone decides.** A replay with the same `eventId` but a different body is a
  duplicate.

## Retry and the dead-letter topic

| Failure | Retried? | Result |
|---|---|---|
| Duplicate `eventId` | Not a failure | Acknowledged; `notification_duplicate_ignored_total` |
| Malformed JSON, invalid envelope, missing IDs, bad amount/currency | No | DLT on the first attempt |
| Unsupported `schemaVersion` | No | DLT on the first attempt |
| Unknown `eventType` | No | DLT on the first attempt |
| Database unavailable, lock or connection problem, constraint violation | Yes: 3 attempts total, 1 s apart | DLT after the 3rd attempt |

Retries are always bounded, and a poison record never stalls the records behind it on its
partition (proven by test). The dead-letter record keeps the **original key and value bytes** and
the original `traceparent` header. Spring Kafka adds `kafka_dlt-original-topic`,
`-original-partition`, `-original-offset`, `-original-consumer-group` (`notification-service`),
`-exception-fqcn`, `-exception-message` and `-exception-stacktrace`. The dead-letter template has
observation disabled, so it does not stamp new trace context over the original.

### DLT inspection and replay: not implemented

There is no inspection table or replay API for `payment.events.v1.notification.DLT`. ADR 0003's
tooling is order-service-local and payment-shaped, and reusing it is not trivial. Inspect by hand:

```bash
docker exec end-to-end-kafka-1 /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic payment.events.v1.notification.DLT \
  --from-beginning --property print.key=true --property print.headers=true
```

**Manual replay caveat.** Republishing a dead-lettered value to `payment.events.v1` (with its
original key) delivers it to **both** consumer groups. order-service deduplicates by `eventId`, so
an event it already applied is ignored. But an event order-service itself had rejected would be
dead-lettered by it again. There is no way yet to replay to notification-service alone. Recorded
as future work.

## Ordering

Records are keyed by `orderId`, so events for one order are handled in publication order. There is
no ordering across orders. `PaymentAuthorized` followed by `PaymentFailed` for the same payment
yields two notifications, in that order.

## Starting a new group

With `auto.offset.reset=earliest`, the first start of the `notification-service` group consumes
every event still retained on `payment.events.v1` and records a notification for each. That is
harmless for `INTERNAL` records (each carries the event's own `occurredAt`). **Before a real
provider is attached**, the delivery step must decide what to do with old events. Otherwise a
first deployment would message customers about historic payments.

## Tracing

`spring.kafka.listener.observation-enabled` makes the container open a consumer span that continues
the producer's W3C `traceparent`, so the notification log lines carry the producer's `traceId`:

```
payment-service       PRODUCER   payment.events.v1 send        (outbox publisher, new trace)
order-service         CONSUMER     payment.events.v1 process
notification-service  CONSUMER     payment.events.v1 process   -> "Notification created …"
```

Nothing injects or forges trace headers. The trace starts at the payment outbox publish, not the
original HTTP request, because trace context is not stored in the outbox row (see
[payment-events.md](payment-events.md#tracing)).

## Observability

Logs (no payloads):

| Event | Level | Logger | Fields |
|---|---|---|---|
| Notification created | INFO | `NotificationEventProcessor` | `notificationId`, `eventId`, `eventType`, `orderId`, `notificationType`, `channel`, `status` |
| Duplicate ignored | INFO | `NotificationEventProcessor` | `eventId`, `eventType`, `orderId` |
| Processing attempt failed | WARN | `PaymentEventListener` | `key` (orderId), exception summary naming the `eventId` when readable |
| Dead-lettered | ERROR | `KafkaConsumerConfig` | source topic/partition/offset, key, DLT topic, exception |

Metrics, with fixed cardinality (`type` is the only tag and has two values):

| Metric | Meaning |
|---|---|
| `notification_events_received_total` | Payment events delivered to the listener (each attempt) |
| `notification_persisted_total{type="PAYMENT_AUTHORIZED"\|"PAYMENT_FAILED"}` | Notifications durably recorded |
| `notification_duplicate_ignored_total` | Deliveries ignored as already handled |
| `notification_failed_total` | Attempts that failed (retried or dead-lettered) |

The "created" counter is deliberately named `persisted`: the Prometheus client reserves the
`_created` suffix for counter creation timestamps and strips it, so a `notification.created` counter
would be exported as a misleading `notification_total`.

Healthy: `persisted + duplicate_ignored` tracks `received`. A rising `notification_failed_total` is
the signal worth alerting on. Spring Kafka's `spring_kafka_listener_seconds` series is also exposed
with `messaging_kafka_consumer_group="notification-service"`.

## Security

`/api/v1/notifications/**` is **not authorization-protected**, like every other service API on the
platform today (auth-service issues tokens, but nothing validates them). The records name users and
describe their payments, so reads must be scoped to the caller's own `userId` (or restricted to
operators) before production.

## Running it locally

```bash
docker compose \
  -f tests/end-to-end/compose.yml \
  -f infrastructure/kafka/compose.kafka.yml \
  up -d kafka payment order notification

# consumer lag for the notification group
docker exec end-to-end-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group notification-service

# notifications for an order
curl -s http://localhost:8089/api/v1/notifications/order/<orderId>
```

## Known limitations

- **No real email/SMS/push delivery.** `CREATED`/`INTERNAL` records only.
- **No contact information.** The recipient is a `userId`.
- **No DLT inspection or replay tooling** for `payment.events.v1.notification.DLT`, and manual
  replay onto the shared topic reaches order-service too.
- **Not exactly-once.** At-least-once plus deduplication.
- **Backfill on first start** of the consumer group (`earliest`).
- **New payment event types are dead-lettered** until this consumer handles them.
- **No authorization** on the inspection API.
- **No retention** for `notification` or `processed_event`.
