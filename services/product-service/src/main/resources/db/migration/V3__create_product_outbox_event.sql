-- Transactional outbox for product lifecycle events (product.events.v1).
--
-- A product create/update/delete and its event row commit in the same transaction; a scheduled
-- publisher sends the stored bytes to Kafka and marks the row PUBLISHED only after the broker
-- acknowledges. Same shape and claiming rules as payment_outbox_event.
CREATE TABLE product_outbox_event (
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
    CONSTRAINT uq_product_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT ck_product_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_product_outbox_event_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_product_outbox_poll
    ON product_outbox_event(status, next_attempt_at, created_at);
CREATE INDEX idx_product_outbox_aggregate_id
    ON product_outbox_event(aggregate_id);

-- Supports the per-product ordering guard: the predecessor lookup only ever inspects PENDING rows
-- for one product, in (created_at, id) order.
CREATE INDEX idx_product_outbox_pending_aggregate
    ON product_outbox_event(aggregate_id, created_at, id)
    WHERE status = 'PENDING';
