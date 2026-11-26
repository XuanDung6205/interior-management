-- TV5: Bảng lưu vector catalog cho Spring AI (MariaDB 11.7+/12.2) và hàng đợi reindex bền vững
-- Áp dụng theo thứ tự sau V001..V012. Không chạy lại V001 trên database đang có dữ liệu.

CREATE TABLE IF NOT EXISTS vector_store (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    content LONGTEXT NOT NULL,
    metadata JSON,
    embedding VECTOR(768) NOT NULL,
    VECTOR INDEX idx_vector_store_embedding (embedding) M=6 DISTANCE=cosine
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS commerce_vector_reindex_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    product_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL DEFAULT 'UPSERT',
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(500),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    processed_at DATETIME(6),
    CONSTRAINT ck_vector_outbox_action CHECK (action IN ('UPSERT', 'DELETE')),
    CONSTRAINT ck_vector_outbox_state CHECK (state IN ('PENDING', 'RETRY', 'SENT', 'DEAD')),
    CONSTRAINT ck_vector_outbox_attempts CHECK (attempts >= 0),
    CONSTRAINT fk_vector_outbox_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE,
    KEY idx_vector_outbox_due (state, next_attempt_at, id)
) ENGINE=InnoDB;
