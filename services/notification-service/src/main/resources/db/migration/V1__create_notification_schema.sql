-- Notification Service schema.
--
-- notification holds one durable record per notification raised from a payment outcome. There is
-- no email/SMS provider yet, so the record itself is the delivery boundary: status CREATED means
-- "durably recorded for the INTERNAL channel", never "sent to a customer".
--
-- order_id, payment_id and user_id are UUID references to other services' data only. There are no
-- foreign keys and no cross-service database access.
CREATE TABLE notification (
    notification_id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    order_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    user_id UUID NOT NULL,
    channel VARCHAR(30) NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Second, independent duplicate guard behind processed_event: one source event can never yield
    -- two notifications of the same type on the same channel, even if the marker were bypassed.
    -- It is keyed per (type, channel) rather than on event_id alone so that one event may fan out
    -- to several notifications later without a schema rewrite.
    CONSTRAINT uq_notification_event_channel_type UNIQUE (event_id, channel, notification_type),
    CONSTRAINT ck_notification_channel CHECK (channel IN ('INTERNAL')),
    CONSTRAINT ck_notification_type CHECK (notification_type IN ('PAYMENT_AUTHORIZED', 'PAYMENT_FAILED')),
    CONSTRAINT ck_notification_status CHECK (status IN ('CREATED'))
);

-- Newest-first listing, the default API view (notification_id is the stable tie-breaker).
CREATE INDEX idx_notification_created_at ON notification(created_at DESC, notification_id);
-- GET /notifications/order/{orderId} and the orderId filter, already in listing order.
CREATE INDEX idx_notification_order_id ON notification(order_id, created_at DESC);
-- userId filter.
CREATE INDEX idx_notification_user_id ON notification(user_id, created_at DESC);
-- notificationType / status filters.
CREATE INDEX idx_notification_type_status ON notification(notification_type, status);

-- Consumer-side deduplication for payment.events.v1.
--
-- Kafka delivery is at-least-once, so the same eventId can arrive again after a rebalance, an
-- outbox republish or an operator replay. The event is claimed here with
-- insert ... on conflict (event_id) do nothing in the SAME transaction as the notification insert:
-- the primary key rejects the second claim, and a failed insert rolls the claim back with it.
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    order_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);
