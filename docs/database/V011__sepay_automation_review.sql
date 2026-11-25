-- Additive only. Existing attempts retain NULL expiry and are never backfilled.
ALTER TABLE payments
 ADD COLUMN IF NOT EXISTS sepay_transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 ADD COLUMN IF NOT EXISTS sepay_bank_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 ADD COLUMN IF NOT EXISTS sepay_next_check_at DATETIME(6),
 ADD COLUMN IF NOT EXISTS sepay_lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
 ADD COLUMN IF NOT EXISTS sepay_lease_until DATETIME(6),
 ADD COLUMN IF NOT EXISTS sepay_check_attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN IF NOT EXISTS sepay_last_check_at DATETIME(6),
 ADD COLUMN IF NOT EXISTS sepay_last_check_result VARCHAR(80);
CREATE INDEX IF NOT EXISTS idx_payments_sepay_due ON payments(provider,sepay_next_check_at,sepay_lease_until,id);
ALTER TABLE payment_events
 ADD COLUMN IF NOT EXISTS review_note VARCHAR(1000),
 ADD COLUMN IF NOT EXISTS reviewed_by BIGINT UNSIGNED,
 ADD COLUMN IF NOT EXISTS reviewed_at DATETIME(6);
CREATE INDEX IF NOT EXISTS idx_payment_events_review ON payment_events(provider,processing_status,reviewed_at,id);
