CREATE TABLE dead_letter_event (
    id UUID PRIMARY KEY,
    event_id UUID,
    event_type VARCHAR(100),
    schema_version INTEGER,
    order_id UUID,
    original_topic VARCHAR(255) NOT NULL,
    original_partition INTEGER,
    original_offset BIGINT,
    dlt_topic VARCHAR(255) NOT NULL,
    dlt_partition INTEGER NOT NULL,
    dlt_offset BIGINT NOT NULL,
    dlt_timestamp TIMESTAMPTZ NOT NULL,
    event_key VARCHAR(255),
    payload TEXT NOT NULL,
    exception_class TEXT,
    exception_message TEXT,
    consumer_group VARCHAR(255),
    traceparent VARCHAR(255),
    first_seen_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL,
    replayed_at TIMESTAMPTZ,
    replay_count INTEGER NOT NULL DEFAULT 0,
    last_replay_error TEXT,
    -- One row per physical DLT record. This is what makes ingestion restart-safe: a
    -- redelivered DLT record after an uncommitted offset collides here instead of duplicating.
    CONSTRAINT uq_dead_letter_event_dlt_coordinates UNIQUE (dlt_topic, dlt_partition, dlt_offset),
    CONSTRAINT ck_dead_letter_event_status CHECK (status IN ('NEW', 'REPLAYED', 'REPLAY_FAILED')),
    CONSTRAINT ck_dead_letter_event_replay_count CHECK (replay_count >= 0)
);

-- Newest-first listing, the default operator view.
CREATE INDEX idx_dead_letter_event_first_seen_at ON dead_letter_event(first_seen_at DESC, id);
-- Filter paths exposed by the admin API. event_id is nullable and non-unique on purpose:
-- the same event can be dead-lettered more than once, and a malformed payload has no eventId.
CREATE INDEX idx_dead_letter_event_event_id ON dead_letter_event(event_id);
CREATE INDEX idx_dead_letter_event_order_id ON dead_letter_event(order_id);
CREATE INDEX idx_dead_letter_event_status ON dead_letter_event(status);
