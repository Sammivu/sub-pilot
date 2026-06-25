-- V2: Plans and Customers

-- ── Plans ────────────────────────────────────────────────────────────────────
CREATE TABLE plans (
    id                 VARCHAR(26)    PRIMARY KEY,
    merchant_id        VARCHAR(26)    NOT NULL REFERENCES merchants(id),
    name               VARCHAR(255)   NOT NULL,
    slug               VARCHAR(100)   NOT NULL,                  -- combined with merchant slug for hosted URL
    description        TEXT,
    amount             BIGINT         NOT NULL,                  -- minor units (kobo)
    currency           VARCHAR(3)     NOT NULL DEFAULT 'NGN',
    billing_interval   VARCHAR(20)    NOT NULL,                  -- daily|weekly|monthly|quarterly|yearly|custom
    interval_value     INTEGER        NOT NULL DEFAULT 1,        -- for custom intervals e.g. every 14
    interval_unit      VARCHAR(20),                              -- days|weeks|months (for custom)
    trial_days         INTEGER        NOT NULL DEFAULT 0,
    proration_policy   VARCHAR(20)    NOT NULL DEFAULT 'none',   -- none|credit|charge
    status             VARCHAR(20)    NOT NULL DEFAULT 'draft',  -- draft|published|archived
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    UNIQUE(merchant_id, slug),
    CONSTRAINT chk_plan_status CHECK (status IN ('draft', 'published', 'archived'))
);

CREATE INDEX idx_plans_merchant_id ON plans(merchant_id);
CREATE INDEX idx_plans_status      ON plans(merchant_id, status);

-- ── Customers ────────────────────────────────────────────────────────────────
-- A customer belongs to exactly one merchant (tenant isolation).
-- card_token is the Nomba tokenised card reference — never raw card data.
CREATE TABLE customers (
    id                  VARCHAR(26)  PRIMARY KEY,
    merchant_id         VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    full_name           VARCHAR(255) NOT NULL,
    email               VARCHAR(255) NOT NULL,
    phone               VARCHAR(50),
    nomba_customer_id   VARCHAR(255),                            -- Nomba's customer reference
    card_token          VARCHAR(500),                            -- Nomba tokenised card ref
    card_last4          VARCHAR(4),
    card_expiry         VARCHAR(10),
    card_brand          VARCHAR(50),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE(merchant_id, email)
);

CREATE INDEX idx_customers_merchant_id ON customers(merchant_id);
CREATE INDEX idx_customers_email       ON customers(merchant_id, email);