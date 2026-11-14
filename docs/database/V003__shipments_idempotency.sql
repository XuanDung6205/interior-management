-- Apply after V002 to the selected furniture_shop schema.
ALTER TABLE shipments ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_shipments_idempotency ON shipments(idempotency_key);
