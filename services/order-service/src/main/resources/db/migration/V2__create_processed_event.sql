CREATE TABLE processed_event (event_id UUID PRIMARY KEY,event_type VARCHAR(100) NOT NULL,order_id UUID NOT NULL,processed_at TIMESTAMPTZ NOT NULL);
CREATE INDEX idx_processed_event_order_id ON processed_event(order_id);
