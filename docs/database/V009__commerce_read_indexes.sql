-- Measured EXPLAIN: global first-page order/payment lists avoid filesort.
-- Deep offsets may still choose full scan; no forced plan or HTTP latency guarantee.
CREATE INDEX IF NOT EXISTS idx_orders_time ON orders(placed_at,id);
CREATE INDEX IF NOT EXISTS idx_payments_time ON payments(created_at,id);
CREATE INDEX IF NOT EXISTS idx_payments_status_time ON payments(status,created_at,id);
