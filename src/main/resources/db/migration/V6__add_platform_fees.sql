-- V6: SubPilot Platform Fees
--
-- SubPilot (like Paystack, Stripe, Nomba itself) takes a cut of every
-- successful subscription charge. This migration adds:
--   1. Per-merchant fee override columns (falls back to platform default if null)
--   2. Fee + net amount snapshot on invoices (so historical invoices keep the
--      fee rate that applied at the time, even if the platform default changes later)
--   3. An immutable platform_fees ledger — one row per successful charge —
--      which is the source of truth for SubPilot's own revenue reporting.

-- ── Merchant-level fee override (nullable -> falls back to platform default) ─
ALTER TABLE merchants
    ADD COLUMN fee_bps INTEGER,              -- basis points, e.g. 150 = 1.5%. NULL = use platform default
    ADD COLUMN fee_fixed_minor BIGINT;        -- fixed fee in minor units (kobo). NULL = use platform default

COMMENT ON COLUMN merchants.fee_bps IS 'Per-merchant override of platform take-rate in basis points. NULL falls back to subpilot.fees.default-bps.';
COMMENT ON COLUMN merchants.fee_fixed_minor IS 'Per-merchant override of fixed fee per transaction, in minor units. NULL falls back to subpilot.fees.default-fixed-minor.';

-- ── Invoice fee snapshot ──────────────────────────────────────────────────────
-- Captured at the moment a charge succeeds, so the fee rate is locked to the
-- invoice even if merchant or platform defaults change afterwards.
ALTER TABLE invoices
    ADD COLUMN platform_fee_amount BIGINT NOT NULL DEFAULT 0,   -- minor units, SubPilot's cut
    ADD COLUMN net_amount BIGINT NOT NULL DEFAULT 0,            -- amount - platform_fee_amount (merchant payout)
    ADD COLUMN fee_bps_applied INTEGER,                          -- the bps rate actually used
    ADD COLUMN fee_fixed_applied BIGINT,                         -- the fixed fee actually used
    ADD COLUMN nomba_reference VARCHAR(255);                     -- Nomba transaction ref for the successful charge

-- ── Platform Fees ledger (append-only, SubPilot's own revenue record) ────────
CREATE TABLE platform_fees (
    id                  VARCHAR(26)  PRIMARY KEY,
    merchant_id         VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    invoice_id          VARCHAR(26)  NOT NULL REFERENCES invoices(id),
    payment_attempt_id  VARCHAR(26)  REFERENCES payment_attempts(id),
    gross_amount        BIGINT       NOT NULL,   -- total amount charged to the customer
    fee_amount          BIGINT       NOT NULL,   -- SubPilot's cut
    net_amount          BIGINT       NOT NULL,   -- merchant payout (gross - fee)
    currency            VARCHAR(3)   NOT NULL DEFAULT 'NGN',
    fee_bps_applied     INTEGER      NOT NULL,
    fee_fixed_applied   BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_platform_fees_merchant   ON platform_fees(merchant_id);
CREATE INDEX idx_platform_fees_invoice    ON platform_fees(invoice_id);
CREATE INDEX idx_platform_fees_created_at ON platform_fees(created_at);

-- Refunds also need to reverse a proportional share of the platform fee.
ALTER TABLE refunds
    ADD COLUMN platform_fee_refunded BIGINT NOT NULL DEFAULT 0;