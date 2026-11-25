-- Nullable snapshots preserve all existing COD/manual payments. No secrets stored.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS sepay_code VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS sepay_bank VARCHAR(50),
    ADD COLUMN IF NOT EXISTS sepay_account_number VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS sepay_account_holder VARCHAR(150),
    ADD COLUMN IF NOT EXISTS sepay_content VARCHAR(200);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_sepay_code ON payments(sepay_code);
