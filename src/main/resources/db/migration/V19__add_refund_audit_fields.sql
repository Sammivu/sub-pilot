-- V{next}__add_refund_audit_fields.sql
ALTER TABLE refunds
    ADD COLUMN resolved_by_admin_id VARCHAR(100);