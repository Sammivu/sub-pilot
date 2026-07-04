-- Merchant's external bank account to transfer payouts into — a standard
-- NUBAN + bank code, not a Nomba sub-account (Nomba has no self-serve API
-- to create one for an arbitrary business — see Merchant.java's javadoc
-- on these fields). Nullable: a merchant must configure these (via
-- settings, out of scope for this migration) before /v1/payouts/trigger
-- will succeed.
ALTER TABLE merchants ADD COLUMN payout_bank_account_number VARCHAR(20) NULL;
ALTER TABLE merchants ADD COLUMN payout_bank_code VARCHAR(10) NULL;
ALTER TABLE merchants ADD COLUMN payout_account_name VARCHAR(255) NULL;

CREATE TABLE disbursements (
    id                       VARCHAR(26)  NOT NULL PRIMARY KEY,
    merchant_id              VARCHAR(26)  NOT NULL,
    amount                   BIGINT       NOT NULL, -- sum of netAmount across the invoices this payout covers
    currency                 VARCHAR(3)   NOT NULL DEFAULT 'NGN',
    status                   VARCHAR(20)  NOT NULL DEFAULT 'pending', -- pending | succeeded | failed
    -- The payout covers every PlatformFee-ledgered invoice with
    -- created_at in (period_start, period_end] that hadn't already been
    -- paid out — this is the idempotent "batch since last payout" cursor.
    period_start             TIMESTAMP,
    period_end               TIMESTAMP    NOT NULL,
    invoice_count             INT          NOT NULL DEFAULT 0,
    nomba_transfer_reference VARCHAR(255),
    failure_reason           VARCHAR(500),
    triggered_by_user_id     VARCHAR(26),
    resolved_at              TIMESTAMP,
    created_at                TIMESTAMP   NOT NULL,
    updated_at                TIMESTAMP   NOT NULL
);

CREATE INDEX idx_disbursements_merchant_id ON disbursements(merchant_id);
CREATE INDEX idx_disbursements_merchant_status ON disbursements(merchant_id, status);