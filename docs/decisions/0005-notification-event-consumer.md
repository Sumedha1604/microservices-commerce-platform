# 5. Notification Service as an independent consumer of payment events

- **Status:** Accepted
- **Date:** 2026-09-11
- **Scope:** `notification-service` (new), `shared/common-events`, `api-gateway`, local compose

## Context

Payment outcomes are the moments a customer needs to hear about: an authorized payment, or a
failed one. Both already exist as durable events. payment-service publishes `PaymentAuthorized`
and `PaymentFailed` on `payment.events.v1` through its transactional outbox (ADR 0002), and
order-service consumes them to confirm or cancel orders (ADR 0001).

What the platform does **not** have:

- **No email, SMS or push provider.** Nothing is configured, and none is in scope.
- **No contact details anywhere near the event stream.** auth-service stores an email for login;
  user-service stores profiles. Neither publishes events, and the payment events carry only
  `paymentId`, `orderId`, `userId`, the amount/currency or the failure reason.
- **No authorization on service APIs.** auth-service issues tokens; no service or gateway route
  validates them.

The payload check that decided the event source: both payment events carry every identifier a
useful notification record needs (`eventId`, `occurredAt`, `paymentId`, `orderId`, `userId`), plus
the amount and currency for an authorization and the failure reason for a failure. Nothing has
to be looked up.

## Decision

**A new, independently deployable `notification-service` consumes `payment.events.v1` directly**,
in its own consumer group `notification-service`, and handles exactly `PaymentAuthorized` and
`PaymentFailed`. payment-service, order-service and checkout-service are unchanged.

**Notification types are named for the fact observed: `PAYMENT_AUTHORIZED` and `PAYMENT_FAILED`.**
`ORDER_CONFIRMED`/`ORDER_CANCELLED` were rejected because this service never observes the order
outcome. order-service can decline an authorization (for an order already `CANCELLED` it
dead-letters the event rather than confirming), so a notification that says "order confirmed"
would sometimes be false. The message text follows the same rule: "your payment … was authorized",
never "your order is confirmed".

**The durable record is the delivery boundary.** Each notification is written with
`channel = INTERNAL` and `status = CREATED`, meaning "recorded", and nothing more. There is no
`SENT` or `DELIVERED` status, because nothing is sent; a mock provider that flipped a status would
make the data lie. Recipient is the event's `userId`, a UUID reference; no contact information is
invented or resolved.

**No synchronous enrichment.** The consumer never calls order-service, user-service or anything
else while handling an event. Everything a notification says comes from the event. A notification
consumer that needs order-service to be up would couple a customer-communication path to the
availability of a core service, stall its partition whenever that service is slow, and turn every
redelivery into extra load on it.

**Idempotency: a `processed_event` claim plus a unique constraint, in one transaction.**

1. `insert into processed_event … on conflict (event_id) do nothing` claims the `eventId`. It
   returns 1 (this transaction owns it) or 0 (already handled → `DUPLICATE`, nothing written).
2. The notification row is inserted and flushed.
3. Commit. Only then does the container commit the Kafka offset (`AckMode.RECORD`).

A concurrent second delivery blocks on the claim until the first commits, then gets 0. No
"exists, then insert" check is used as a guard, and no exception is interpreted as a duplicate.
If the notification insert fails, the claim rolls back with it, so the retry is processed rather
than swallowed. `processed_event` was chosen over a bare unique `event_id` on `notification` because one
event may produce several notifications later (another channel, another audience) without
redesigning deduplication. `notification` still carries
`UNIQUE (event_id, channel, notification_type)` as a second, database-enforced guard.

**Its own dead-letter topic: `payment.events.v1.notification.DLT`.** order-service owns
`payment.events.v1.DLT` and its inspection/replay tooling ingests *everything* on that topic. If
notification-service shared it, a record only notification-service rejected would show up as an
order-service failure, and "replaying" it would republish it to order-service as well. DLT
ownership follows the consumer. The name is `<source>.<consumer>.DLT`, and the constant lives in
`shared/common-events` next to the other topic names, as the existing DLT constants do.

**Failure handling mirrors order-service and inventory-service exactly:** raw `String` values
parsed by our own code (no type headers), 3 attempts with a 1 s fixed backoff for anything
transient, immediate dead-letter for `NonRetryableEventException` (malformed JSON, unsupported
`schemaVersion`, unknown `eventType`, missing `paymentId`/`orderId`/`userId`, missing or negative
amount, invalid currency). Three consumers with three different failure behaviours would be a
maintenance trap.

**Read-only REST API**, under `/api/v1/notifications`, routed through the gateway. There is no
create or send endpoint.

## Alternatives considered

- **order-service emits `OrderConfirmed`/`OrderCancelled` and notification-service consumes those.**
  That would describe the order outcome truthfully, but it needs a new contract and a second outbox
  in order-service, which already has one for compensation. Nothing in this milestone requires the
  order outcome specifically. Worth revisiting when a notification genuinely needs it (for example,
  "your order has shipped").
- **Consume `order.compensation.v1`.** Rejected: it is an internal stock-release intent, not a
  customer-facing fact, and it only exists for failures.
- **Call order-service (or user-service) to enrich notifications.** Rejected for the runtime-coupling
  reasons above. Contact details belong to a future user/contact event or a delivery worker, not
  to event processing.
- **Unique `event_id` on `notification` only.** Simpler, but it hard-codes one notification per
  event. The `processed_event` claim costs one table and keeps fan-out open.
- **Share `payment.events.v1.DLT`.** Rejected on ownership, as described above.
- **A `SENT`/`DELIVERED` status via a logging "mock provider".** Rejected: it would misreport
  delivery.
- **A generic communications platform** (templates, preferences, provider routing). Out of scope.

## Consequences

- Every payment outcome now leaves one durable, queryable notification record, independently of
  whether order-service handled the same event.
- Two consumer groups read `payment.events.v1`. Each has its own offsets, lag and dead-letter
  topic, and neither can slow the other down.
- **Delivery is at-least-once plus consumer-side deduplication.** It is not exactly-once. An
  outbox republish, a rebalance or an operator replay can deliver an `eventId` again, and
  `processed_event` absorbs it.
- **Ordering is per order** (records are keyed by `orderId`). A `PaymentAuthorized` and a later
  `PaymentFailed` for the same payment produce two notifications, in publication order.
- **A new consumer group starts from the earliest retained offset.** On first deployment the
  service backfills notifications for payment events still on the topic. That is harmless for
  `INTERNAL` records. Before a real provider is attached, the delivery step must decide what to do
  with old events (for example, skip anything whose `occurredAt` is older than a threshold).
- **A new `eventType` on `payment.events.v1` is dead-lettered by this consumer until it is taught
  about it**, the same as order-service.
- **Dead-lettered notification events have no inspection or replay tooling.** ADR 0003's tooling is
  order-service-local and payment-shaped. Manual replay onto `payment.events.v1` is also seen by
  order-service, which deduplicates by `eventId`. See `docs/events/notification-events.md`.
- **Notification APIs are unauthenticated**, like every other service API today. The records name
  users and describe their payments, so this has to be closed before production.
- The trace is continuous from payment-service's outbox publish to this consumer. It does not
  continue the original HTTP request's trace, for the same outbox reason as order-service.

## Future work

- A delivery worker and a real provider (with its own status transitions such as `SENT` and
  `FAILED`, added by migration), fed by a contact-details source this service is allowed to hold.
- DLT inspection/replay for `payment.events.v1.notification.DLT`, ideally by generalising ADR 0003's
  tooling rather than copying it.
- Authorization on `/api/v1/notifications/**`, scoping reads to the caller's own `userId`.
- Retention for `notification` and `processed_event`.
