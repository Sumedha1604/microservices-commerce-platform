ALTER TABLE cart_items ADD COLUMN version BIGINT;

UPDATE cart_items SET version = 0 WHERE version IS NULL;

ALTER TABLE cart_items ALTER COLUMN version SET DEFAULT 0;

ALTER TABLE cart_items ALTER COLUMN version SET NOT NULL;
