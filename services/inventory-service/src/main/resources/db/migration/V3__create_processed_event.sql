-- Consumer-side deduplication for inventory compensation.
--
-- This platform has no reservation rows: a reservation is a counter on the inventory row, so
-- there is no reservation status to guard a replay with. The event id is therefore the only
-- thing that can make a release idempotent, and it has to be recorded in the SAME transaction
-- as the stock change - the primary key is what rejects the second attempt.
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    order_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_processed_event_order_id ON processed_event(order_id);
