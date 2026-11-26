-- Catalog ownership is nullable: pre-existing generic vector rows remain untouched.
ALTER TABLE vector_store
    ADD COLUMN IF NOT EXISTS catalog_product_id BIGINT,
    ADD COLUMN IF NOT EXISTS catalog_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(80),
    ADD COLUMN IF NOT EXISTS indexed_at DATETIME(6);
CREATE UNIQUE INDEX IF NOT EXISTS uq_vector_catalog_product ON vector_store(catalog_product_id);
ALTER TABLE vector_store ADD CONSTRAINT fk_vector_store_catalog
    FOREIGN KEY IF NOT EXISTS (catalog_product_id) REFERENCES products(id) ON DELETE CASCADE;

-- Claims remain PENDING/RETRY; lease tokens fence workers without changing V013 states.
ALTER TABLE commerce_vector_reindex_outbox
    ADD COLUMN IF NOT EXISTS requested_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN IF NOT EXISTS lease_until DATETIME(6),
    ADD COLUMN IF NOT EXISTS requested_by BIGINT,
    ADD COLUMN IF NOT EXISTS retry_reason VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS retried_by BIGINT,
    ADD COLUMN IF NOT EXISTS retried_at DATETIME(6);
CREATE INDEX IF NOT EXISTS idx_vector_outbox_product ON commerce_vector_reindex_outbox(product_id,id);
CREATE INDEX IF NOT EXISTS idx_vector_outbox_lease ON commerce_vector_reindex_outbox(state,lease_until,next_attempt_at,id);
