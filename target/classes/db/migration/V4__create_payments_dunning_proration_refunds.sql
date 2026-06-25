-- V4: Payment Attempts, Dunning Engine, Proration Records, Refunds

-- ── Payment Attempts ─────────────────────────────────────────────────────────
-- One attempt per charge call to Nomba. Idempotency key prevents double charges.
CREATE TABLE payment_attempts (
    id                VARCHAR(26)  PRIMARY KEY,
    merchant_id       VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    invoice_id        VARCHAR(26)  NOT NULL REFERENCES invoices(id),
    subscription_id   VARCHAR(26)  NOT NULL REFERENCES subscriptions(id),
    idempotency_key   VARCHAR(255) NOT NULL UNIQUE,              -- subscription_id + period_start
    nomba_reference   VARCHAR(255),                              -- Nomba transaction ref
    provider          VARCHAR(50)  NOT NULL DEFAULT 'nomba',
    amount            BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL DEFAULT 'NGN',
    status            VARCHAR(20)  NOT NULL DEFAULT 'pending',
    -- valid: pending|processing|succeeded|failed
    failure_code      VARCHAR(100),
    failure_reason    TEXT,
    attempted_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at       TIMESTAMPTZ
);

CREATE INDEX idx_payment_attempts_merchant     ON payment_attempts(merchant_id);
CREATE INDEX idx_payment_attempts_invoice      ON payment_attempts(invoice_id);
CREATE INDEX idx_payment_attempts_idempotency  ON payment_attempts(idempotency_key);
CREATE INDEX idx_payment_attempts_nomba_ref    ON payment_attempts(nomba_reference);

-- ── Dunning Campaigns (merchant-level configuration) ─────────────────────────
CREATE TABLE dunning_campaigns (
    id                  VARCHAR(26)  PRIMARY KEY,
    merchant_id         VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    name                VARCHAR(255) NOT NULL DEFAULT 'Default Campaign',
    grace_period_days   INTEGER      NOT NULL DEFAULT 21,
    max_attempts        INTEGER      NOT NULL DEFAULT 4,
    is_default          BOOLEAN      NOT NULL DEFAULT TRUE,
    cancel_after_exhaustion BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dunning_campaigns_merchant ON dunning_campaigns(merchant_id);

-- ── Dunning Steps (per campaign configuration) ───────────────────────────────
CREATE TABLE dunning_steps (
    id               VARCHAR(26)  PRIMARY KEY,
    campaign_id      VARCHAR(26)  NOT NULL REFERENCES dunning_campaigns(id),
    step_number      INTEGER      NOT NULL,
    day_offset       INTEGER      NOT NULL,                      -- days after initial failure
    action           VARCHAR(30)  NOT NULL,                      -- retry_charge|send_email|both
    email_template   VARCHAR(100),                               -- payment_failed|final_warning|service_suspended
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE(campaign_id, step_number)
);

CREATE INDEX idx_dunning_steps_campaign ON dunning_steps(campaign_id);

-- ── Dunning Executions (per-subscription dunning run) ────────────────────────
CREATE TABLE dunning_executions (
    id               VARCHAR(26)  PRIMARY KEY,
    merchant_id      VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    subscription_id  VARCHAR(26)  NOT NULL REFERENCES subscriptions(id),
    invoice_id       VARCHAR(26)  NOT NULL REFERENCES invoices(id),
    campaign_id      VARCHAR(26)  NOT NULL REFERENCES dunning_campaigns(id),
    current_step     INTEGER      NOT NULL DEFAULT 0,
    status           VARCHAR(20)  NOT NULL DEFAULT 'active',     -- active|resolved|exhausted|cancelled
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at      TIMESTAMPTZ
);

CREATE INDEX idx_dunning_executions_merchant      ON dunning_executions(merchant_id);
CREATE INDEX idx_dunning_executions_subscription  ON dunning_executions(subscription_id);
CREATE INDEX idx_dunning_executions_status        ON dunning_executions(status) WHERE status = 'active';

-- ── Retry Schedules (individual scheduled actions within a dunning execution) ─
CREATE TABLE retry_schedules (
    id                    VARCHAR(26)  PRIMARY KEY,
    merchant_id           VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    dunning_execution_id  VARCHAR(26)  NOT NULL REFERENCES dunning_executions(id),
    subscription_id       VARCHAR(26)  NOT NULL REFERENCES subscriptions(id),
    step_number           INTEGER      NOT NULL,
    scheduled_at          TIMESTAMPTZ  NOT NULL,
    executed_at           TIMESTAMPTZ,
    status                VARCHAR(20)  NOT NULL DEFAULT 'pending' -- pending|executed|skipped
);

CREATE INDEX idx_retry_schedules_execution   ON retry_schedules(dunning_execution_id);
CREATE INDEX idx_retry_schedules_scheduled   ON retry_schedules(scheduled_at) WHERE status = 'pending';

-- ── Proration Records ────────────────────────────────────────────────────────
CREATE TABLE proration_records (
    id                  VARCHAR(26)  PRIMARY KEY,
    merchant_id         VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    subscription_id     VARCHAR(26)  NOT NULL REFERENCES subscriptions(id),
    previous_plan_id    VARCHAR(26)  NOT NULL REFERENCES plans(id),
    new_plan_id         VARCHAR(26)  NOT NULL REFERENCES plans(id),
    credit_amount       BIGINT       NOT NULL DEFAULT 0,         -- minor units
    charge_amount       BIGINT       NOT NULL DEFAULT 0,         -- minor units
    invoice_id          VARCHAR(26)  REFERENCES invoices(id),
    applied_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_proration_records_subscription ON proration_records(subscription_id);

-- ── Refunds ──────────────────────────────────────────────────────────────────
-- Added based on frontend BACKEND_HANDOFF.md. Uses Nomba Transfers API.
CREATE TABLE refunds (
    id                  VARCHAR(26)  PRIMARY KEY,
    merchant_id         VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    invoice_id          VARCHAR(26)  NOT NULL REFERENCES invoices(id),
    payment_attempt_id  VARCHAR(26)  REFERENCES payment_attempts(id),
    amount              BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL DEFAULT 'NGN',
    reason              TEXT,
    status              VARCHAR(20)  NOT NULL DEFAULT 'pending', -- pending|succeeded|failed
    provider_reference  VARCHAR(255),                            -- Nomba transfer ref
    idempotency_key     VARCHAR(255) NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refunds_merchant   ON refunds(merchant_id);
CREATE INDEX idx_refunds_invoice    ON refunds(invoice_id);
