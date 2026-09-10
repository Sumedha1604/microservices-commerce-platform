# Notification Service database

Own PostgreSQL database (`notification_db`), migrated by Flyway. Migration
`V1__create_notification_schema.sql` creates two tables. There are **no foreign keys**. `order_id`,
`payment_id` and `user_id` are UUID references to data owned by other services, with no
cross-service database access (verified in `NotificationPostgresIntegrationTest`).

## `notification`

One durable record per notification raised from a payment event.

| Column | Type | Constraints |
|---|---|---|
| `notification_id` | UUID | primary key |
| `event_id` | UUID | `NOT NULL` |
| `event_type` | VARCHAR(100) | `NOT NULL` |
| `order_id` | UUID | `NOT NULL` |
| `payment_id` | UUID | `NOT NULL` |
| `user_id` | UUID | `NOT NULL` |
| `channel` | VARCHAR(30) | `NOT NULL`, `CHECK (channel IN ('INTERNAL'))` |
| `notification_type` | VARCHAR(50) | `NOT NULL`, `CHECK (notification_type IN ('PAYMENT_AUTHORIZED', 'PAYMENT_FAILED'))` |
| `subject` | VARCHAR(255) | `NOT NULL` |
| `message` | TEXT | `NOT NULL` |
| `status` | VARCHAR(30) | `NOT NULL`, `CHECK (status IN ('CREATED'))` |
| `occurred_at` | TIMESTAMPTZ | `NOT NULL`, the source event's `occurredAt` |
| `created_at` | TIMESTAMPTZ | `NOT NULL` |
| `updated_at` | TIMESTAMPTZ | `NOT NULL` |

Table constraint `uq_notification_event_channel_type UNIQUE (event_id, channel, notification_type)`
is a second duplicate guard behind `processed_event`. It is scoped per type and channel, rather
than a bare unique `event_id`, so one event can fan out to several notifications later.

The check constraints are deliberate. The database refuses a status such as `SENT` or a channel
such as `EMAIL` that the application does not have. Adding one is a new migration, which is exactly
when that decision should be made.

### Indexes

| Index | Columns | Serves |
|---|---|---|
| `notification_pkey` | `notification_id` | detail lookup |
| `uq_notification_event_channel_type` | `event_id, channel, notification_type` | duplicate guard; lookups by `event_id` |
| `idx_notification_created_at` | `created_at DESC, notification_id` | default newest-first listing |
| `idx_notification_order_id` | `order_id, created_at DESC` | `GET /notifications/order/{orderId}` and the `orderId` filter |
| `idx_notification_user_id` | `user_id, created_at DESC` | `userId` filter |
| `idx_notification_type_status` | `notification_type, status` | `notificationType`/`status` filters |

## `processed_event`

Consumer-side deduplication for `payment.events.v1`.

| Column | Type | Constraints |
|---|---|---|
| `event_id` | UUID | primary key |
| `event_type` | VARCHAR(100) | `NOT NULL` |
| `order_id` | UUID | `NOT NULL` |
| `processed_at` | TIMESTAMPTZ | `NOT NULL` |

A row is claimed with `insert … on conflict (event_id) do nothing` **in the same transaction** as the
notification insert, before it. 0 rows means the event was already handled, and nothing else is
written. A concurrent second claim waits on the first transaction instead of racing it. If the
notification insert fails, the claim rolls back with it. See
[../events/notification-events.md](../events/notification-events.md#delivery-and-duplicates).

## Retention

Neither table is pruned. Both grow with payment volume; retention/archival is future work.
