-- Durable outbox for events order-service publishes about its own aggregate.
--
-- Mirrors payment_outbox_event deliberately: the reliability problem is identical (a state
-- change and a Kafka send that must not drift apart), and two services solving it two different
-- ways would be worse than the duplication. The table is separate because the aggregate,
-- lifecycle and retention are order-service's own.
CREATE TABLE order_outbox_event (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL,
    topic VARCHAR(255) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    next_attempt_at TIMESTAMPTZ,
    -- The eventId is minted once, when the row is written inside the business transaction, and
    -- is reused by every republish attempt forever. That is what makes a crash between the Kafka
    -- ack and the PUBLISHED update safe: the redelivery carries the same id and the consumer
    -- recognises it as a duplicate.
    CONSTRAINT uq_order_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_order_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_order_outbox_event_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_order_outbox_poll
    ON order_outbox_event(status, next_attempt_at, created_at);
CREATE INDEX idx_order_outbox_aggregate_id
    ON order_outbox_event(aggregate_id);

-- Supports the per-aggregate ordering guard: the predecessor lookup only ever
-- inspects PENDING rows for one aggregate, in (created_at, id) order.
CREATE INDEX idx_order_outbox_pending_aggregate
    ON order_outbox_event(aggregate_id, created_at, id)
    WHERE status = 'PENDING';
