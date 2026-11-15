-- Apply after V003 to the selected furniture_shop schema. Keeps the approved 45-table model.
CREATE INDEX IF NOT EXISTS idx_audit_shipment_request
    ON audit_logs(entity_type,action,request_id);
