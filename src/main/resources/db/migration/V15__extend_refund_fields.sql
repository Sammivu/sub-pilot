-- V4 (create_payments_dunning_proration_refunds) already created the
-- `refunds` table, and V6 (add_platform_fees) already added
-- platform_fee_refunded to it. This migration only adds what's still
-- missing for RefundService: failure_reason, requested_by_user_id, and
-- resolved_at. It deliberately does NOT recreate the table or duplicate
-- platform_fee_refunded — an earlier draft of this migration did both by
-- mistake (would have failed with "relation already exists" /
-- "column already exists" the moment it ran).
--
-- Note the existing schema's naming: the transfer reference column is
-- provider_reference, not nomba_reference — Refund.java maps its
-- nombaReference Java field onto that existing column name rather than
-- introducing a second, redundant column.
ALTER TABLE refunds
    ADD COLUMN failure_reason VARCHAR(500),
    ADD COLUMN requested_by_user_id VARCHAR(26),
    ADD COLUMN resolved_at TIMESTAMPTZ;
