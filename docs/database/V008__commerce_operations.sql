-- UTC deadlines only for newly opted-in COD orders. No legacy order backfill.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS confirmation_expires_at DATETIME(6) NULL;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS expiry_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS expiry_retry_after DATETIME(6) NULL;
CREATE INDEX IF NOT EXISTS idx_orders_confirmation_expiry ON orders(status,confirmation_expires_at,id);

CREATE TABLE IF NOT EXISTS commerce_notification_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    last_error VARCHAR(100),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    sent_at DATETIME(6),
    CONSTRAINT uq_commerce_outbox_event UNIQUE(event_key),
    CONSTRAINT ck_commerce_outbox_state CHECK(state IN ('PENDING','RETRY','SENT','DEAD')),
    CONSTRAINT ck_commerce_outbox_attempts CHECK(attempts>=0),
    CONSTRAINT fk_commerce_outbox_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_commerce_outbox_recipient FOREIGN KEY(recipient_user_id) REFERENCES users(id) ON DELETE CASCADE,
    KEY idx_commerce_outbox_due(state,next_attempt_at,id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS commerce_notifications (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    outbox_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    read_at DATETIME(6),
    CONSTRAINT uq_commerce_notification_event UNIQUE(outbox_id),
    CONSTRAINT fk_commerce_notification_event FOREIGN KEY(outbox_id) REFERENCES commerce_notification_outbox(id) ON DELETE CASCADE,
    CONSTRAINT fk_commerce_notification_order FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_commerce_notification_recipient FOREIGN KEY(recipient_user_id) REFERENCES users(id) ON DELETE CASCADE,
    KEY idx_commerce_notifications_page(recipient_user_id,created_at,id),
    KEY idx_commerce_notifications_unread(recipient_user_id,read_at,created_at,id)
) ENGINE=InnoDB;
