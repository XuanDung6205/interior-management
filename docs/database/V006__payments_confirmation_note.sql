-- Additive after V005. Private verification evidence; customer APIs exclude it.
ALTER TABLE payments ADD COLUMN IF NOT EXISTS confirmation_note VARCHAR(1000) NULL;
