-- V3: Subscriptions and Invoices

-- ── Subscriptions ────────────────────────────────────────────────────────────
-- The core entity. State machine lives here.
-- subscription_token is the opaque token used in subscriber portal URLs.
CREATE TABLE subscriptions (
    id                      VARCHAR(26)  PRIMARY KEY,
    merchant_id             VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    customer_id             VARCHAR(26)  NOT NULL REFERENCES customers(id),
    plan_id                 VARCHAR(26)  NOT NULL REFERENCES plans(id),
    status                  VARCHAR(20)  NOT NULL DEFAULT 'trialing',
    -- valid: trialing|active|past_due|paused|cancelled|expired

    current_period_start    TIMESTAMPTZ  NOT NULL,
    current_period_end      TIMESTAMPTZ  NOT NULL,
    next_billing_date       TIMESTAMPTZ,

    trial_ends_at           TIMESTAMPTZ,
    cancel_at_period_end    BOOLEAN      NOT NULL DEFAULT FALSE,
    cancelled_at            TIMESTAMPTZ,
    cancellation_reason     TEXT,
    paused_at               TIMESTAMPTZ,

    -- Nomba references
    nomba_customer_ref      VARCHAR(255),
    nomba_card_token_ref    VARCHAR(500),

    -- Opaque token for subscriber portal access (no auth required)
    subscription_token      VARCHAR(36)  NOT NULL UNIQUE DEFAULT gen_random_uuid()::TEXT,

    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscriptions_merchant_id      ON subscriptions(merchant_id);
CREATE INDEX idx_subscriptions_status           ON subscriptions(merchant_id, status);
CREATE INDEX idx_subscriptions_next_billing     ON subscriptions(next_billing_date) WHERE status = 'active';
CREATE INDEX idx_subscriptions_token            ON subscriptions(subscription_token);
CREATE INDEX idx_subscriptions_customer         ON subscriptions(customer_id);

-- ── Invoices ─────────────────────────────────────────────────────────────────
-- One invoice per billing cycle. Generated before charge attempt.
CREATE TABLE invoices (
    id               VARCHAR(26)  PRIMARY KEY,
    merchant_id      VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    subscription_id  VARCHAR(26)  NOT NULL REFERENCES subscriptions(id),
    customer_id      VARCHAR(26)  NOT NULL REFERENCES customers(id),
    invoice_number   VARCHAR(50)  NOT NULL,                      -- e.g. INV-0042 (sequential per merchant)
    amount           BIGINT       NOT NULL,                      -- minor units (kobo)
    currency         VARCHAR(3)   NOT NULL DEFAULT 'NGN',
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',
    -- valid: pending|paid|failed|void|refunded

    due_date         TIMESTAMPTZ  NOT NULL,
    paid_at          TIMESTAMPTZ,
    period_start     TIMESTAMPTZ  NOT NULL,
    period_end       TIMESTAMPTZ  NOT NULL,
    proration_note   TEXT,                                       -- populated for prorated invoices
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    UNIQUE(merchant_id, invoice_number)
);

CREATE INDEX idx_invoices_merchant_id     ON invoices(merchant_id);
CREATE INDEX idx_invoices_subscription    ON invoices(subscription_id);
CREATE INDEX idx_invoices_status          ON invoices(merchant_id, status);
CREATE INDEX idx_invoices_customer        ON invoices(customer_id);

-- Invoice number sequence per merchant (we'll use a DB sequence approach via a counter table)
CREATE TABLE invoice_sequences (
    merchant_id  VARCHAR(26) PRIMARY KEY REFERENCES merchants(id),
    last_value   BIGINT NOT NULL DEFAULT 0
);
