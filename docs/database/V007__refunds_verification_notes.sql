-- Private staff evidence; financial history and public refund reason remain unchanged.
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS confirmation_note VARCHAR(1000) NULL;
ALTER TABLE refunds ADD COLUMN IF NOT EXISTS resolution_note VARCHAR(1000) NULL;
