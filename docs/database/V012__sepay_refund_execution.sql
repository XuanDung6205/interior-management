-- A claimed provider intent is never converted back to a manual refund.
-- Nullable columns preserve every existing refund and its proof workflow.
ALTER TABLE refunds
    ADD COLUMN IF NOT EXISTS sepay_execution_state VARCHAR(20),
    ADD COLUMN IF NOT EXISTS sepay_request_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS sepay_transaction_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS sepay_bank_account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS sepay_original_reference VARCHAR(150) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS sepay_refund_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS sepay_requested_by BIGINT,
    ADD COLUMN IF NOT EXISTS sepay_requested_at DATETIME(6),
    ADD COLUMN IF NOT EXISTS sepay_checked_at DATETIME(6),
    ADD COLUMN IF NOT EXISTS sepay_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sepay_retry_at DATETIME(6),
    ADD COLUMN IF NOT EXISTS sepay_lease_until DATETIME(6),
    ADD COLUMN IF NOT EXISTS sepay_lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS sepay_error_code VARCHAR(80);
CREATE UNIQUE INDEX IF NOT EXISTS uq_refunds_sepay_request_key ON refunds(sepay_request_key);
CREATE UNIQUE INDEX IF NOT EXISTS uq_refunds_sepay_refund_id ON refunds(sepay_refund_id);
CREATE INDEX IF NOT EXISTS idx_refunds_sepay_due ON refunds(status,sepay_execution_state,sepay_retry_at,sepay_lease_until);
