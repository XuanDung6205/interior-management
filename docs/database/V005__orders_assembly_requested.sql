-- Additive after V004. Preserve the requested service even when its fee is zero.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS assembly_requested BOOLEAN NOT NULL DEFAULT FALSE CHECK(assembly_requested IN (0,1));
-- Historical checkout only stored the fee; positive fee is unambiguous evidence.
UPDATE orders SET assembly_requested=TRUE WHERE assembly_fee>0 AND assembly_requested=FALSE;
