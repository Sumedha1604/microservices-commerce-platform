CREATE TABLE payment_outbox_event (
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
    CONSTRAINT uq_payment_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_payment_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_payment_outbox_event_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_payment_outbox_poll
    ON payment_outbox_event(status, next_attempt_at, created_at);
CREATE INDEX idx_payment_outbox_aggregate_id
    ON payment_outbox_event(aggregate_id);

-- Supports the per-aggregate ordering guard: the predecessor lookup only ever
-- inspects PENDING rows for one aggregate, in (created_at, id) order.
CREATE INDEX idx_payment_outbox_pending_aggregate
    ON payment_outbox_event(aggregate_id, created_at, id)
    WHERE status = 'PENDING';
