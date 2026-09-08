# 2. Transactional Outbox for payment events

- **Status:** Accepted
- **Date:** 2026-09-07
- **Scope:** `payment-service`

## Context

ADR 0001 published after the payment database transaction committed. A crash or Kafka outage
between those two operations could leave an authorized or failed payment without its event.
Kafka transactions cannot make a PostgreSQL state change atomic with a Kafka send.

## Decision

Write a `payment_outbox_event` row in the same PostgreSQL transaction as every payment
authorization or failure. The row stores the complete v1 envelope JSON, its permanent
`eventId`, event type, topic, and `orderId` key. No Kafka call occurs in the HTTP transaction.

A scheduled in-process publisher selects a configurable bounded batch of eligible `PENDING`
rows using PostgreSQL `FOR UPDATE SKIP LOCKED`. It holds those row locks while issuing the
bounded Kafka sends and waiting for acknowledgements. This keeps multiple payment-service
instances from publishing the same row concurrently. The small batch and bounded producer/send
timeouts limit lock duration and favor straightforward correctness over a lease protocol.

After acknowledgement the row becomes `PUBLISHED`. Failure leaves it `PENDING`, increments
`attempt_count`, records `last_error`, and sets `next_attempt_at` with exponential backoff capped
at five minutes. There is no terminal retry count: temporary infrastructure failure cannot
silently abandon a payment event.

Publication order is preserved per aggregate. The claim query also requires that no earlier
unpublished row exists for the same `aggregate_id`, using the persisted `(created_at, id)` order,
supported by a partial index on `PENDING` rows. Without it a payment that goes `AUTHORIZED` then
`FAILED` could publish `PaymentFailed` while `PaymentAuthorized` was still in backoff, and the
late `PaymentAuthorized` would then dead-letter against an already-cancelled order. The guard is
scoped to a single aggregate, so an order in backoff never blocks another order; the cost is that
one polling cycle claims at most one row per aggregate.

An interrupt ends the current cycle after the row being sent. That row records a failed attempt
like any other; the rows already claimed behind it are left untouched, so a shutdown does not
consume retry budget for sends that were never attempted.

## Consequences

- Payment state and the durable intent to publish are atomic, closing the commit-to-send loss
  window.
- Delivery remains at-least-once, not exactly once. A crash after Kafka acknowledgement but
  before the `PUBLISHED` update commits causes republication of the same stored envelope and
  `eventId`. Order Service's `processed_event` key makes that duplicate safe.
- Kafka outages do not fail or roll back the original payment HTTP request. Rows retry durably.
- Publisher transactions hold row locks during bounded Kafka acknowledgement waits. Throughput
  can be tuned with batch size and poll interval; a future lease design is possible if this
  becomes a bottleneck.
- Kafka producer observations start asynchronously. Trace context from the original HTTP request
  is not persisted or fabricated; durable trace continuation is deferred.
- Events for one order publish in the order they were written; unrelated orders progress
  independently. Delivery is still at-least-once, so ordering does not imply uniqueness.
- Published rows are retained forever. Retention or archival of `payment_outbox_event` is a
  future concern, not addressed here.
- Retry eligibility compares the database's `now()` against a `next_attempt_at` written from the
  publishing instance's clock. Meaningful skew shifts retry timing; it does not affect ordering
  or correctness. Sourcing both from the database would remove the assumption.
- `attempt_count` records failed publish attempts, not total attempts: a row that succeeds on its
  first send stays at `0`.
- Consumer retries and DLT behavior from ADR 0001 are unchanged. Checkout stays synchronous.
