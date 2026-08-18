ALTER TABLE inventory ADD COLUMN version BIGINT;

UPDATE inventory SET version = 0 WHERE version IS NULL;

ALTER TABLE inventory ALTER COLUMN version SET DEFAULT 0;

ALTER TABLE inventory ALTER COLUMN version SET NOT NULL;
