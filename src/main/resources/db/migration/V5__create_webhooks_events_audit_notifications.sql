-- V5: Webhooks, Events, Audit Logs, Notifications, ShedLock

-- ── Webhook Endpoints (merchant-registered destinations) ────────────────────
CREATE TABLE webhook_endpoints (
    id                  VARCHAR(26)  PRIMARY KEY,
    merchant_id         VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    url                 TEXT         NOT NULL,
    description         VARCHAR(255),
    subscribed_events   TEXT[]       NOT NULL DEFAULT '{}',      -- array of event type strings
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    signing_secret_hash VARCHAR(255) NOT NULL,                   -- HMAC secret per-endpoint
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_endpoints_merchant ON webhook_endpoints(merchant_id);

-- ── Webhook Deliveries (outbound delivery attempts) ─────────────────────────
CREATE TABLE webhook_deliveries (
    id                   VARCHAR(26)  PRIMARY KEY,
    merchant_id          VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    endpoint_id          VARCHAR(26)  NOT NULL REFERENCES webhook_endpoints(id),
    event_id             VARCHAR(26)  NOT NULL,                  -- references events.id
    status               VARCHAR(20)  NOT NULL DEFAULT 'pending',-- pending|succeeded|failed
    attempt_count        INTEGER      NOT NULL DEFAULT 0,
    last_attempted_at    TIMESTAMPTZ,
    next_retry_at        TIMESTAMPTZ,
    response_status      INTEGER,
    response_body        TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_deliveries_merchant    ON webhook_deliveries(merchant_id);
CREATE INDEX idx_webhook_deliveries_endpoint    ON webhook_deliveries(endpoint_id);
CREATE INDEX idx_webhook_deliveries_next_retry  ON webhook_deliveries(next_retry_at) WHERE status = 'pending';

-- ── Webhook Logs (inbound from Nomba) ────────────────────────────────────────
CREATE TABLE webhook_logs (
    id                VARCHAR(26)  PRIMARY KEY,
    merchant_id       VARCHAR(26)  REFERENCES merchants(id),     -- nullable until resolved
    nomba_event_type  VARCHAR(100),
    nomba_event_id    VARCHAR(255) UNIQUE,                       -- for deduplication
    payload           JSONB        NOT NULL,
    signature_valid   BOOLEAN      NOT NULL DEFAULT FALSE,
    processed_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_webhook_logs_nomba_event_id ON webhook_logs(nomba_event_id);

-- ── Events (append-only internal event log) ──────────────────────────────────
-- This is the source of truth for analytics, webhooks, and audit.
-- NEVER update or delete rows from this table.
CREATE TABLE events (
    id               VARCHAR(26)  PRIMARY KEY,
    merchant_id      VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    type             VARCHAR(100) NOT NULL,
    resource_type    VARCHAR(50),                                -- subscription|invoice|plan|payment|dunning|webhook
    resource_id      VARCHAR(26),
    subscription_id  VARCHAR(26),
    payload          JSONB        NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_events_merchant_id     ON events(merchant_id);
CREATE INDEX idx_events_type            ON events(merchant_id, type);
CREATE INDEX idx_events_subscription    ON events(subscription_id);
CREATE INDEX idx_events_resource        ON events(resource_type, resource_id);
CREATE INDEX idx_events_created_at      ON events(merchant_id, created_at DESC);

-- ── Audit Logs (merchant-initiated actions with before/after snapshot) ────────
CREATE TABLE audit_logs (
    id               VARCHAR(26)  PRIMARY KEY,
    merchant_id      VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    actor_id         VARCHAR(26)  NOT NULL,                      -- user_id or api_key_id
    actor_type       VARCHAR(20)  NOT NULL,                      -- user|api_key
    action           VARCHAR(100) NOT NULL,
    resource_type    VARCHAR(50)  NOT NULL,
    resource_id      VARCHAR(26)  NOT NULL,
    before_snapshot  JSONB,
    after_snapshot   JSONB,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_merchant  ON audit_logs(merchant_id);
CREATE INDEX idx_audit_logs_resource  ON audit_logs(resource_type, resource_id);

-- ── Notification Logs (email sends) ──────────────────────────────────────────
CREATE TABLE notification_logs (
    id               VARCHAR(26)  PRIMARY KEY,
    merchant_id      VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    subscription_id  VARCHAR(26)  REFERENCES subscriptions(id),
    recipient_email  VARCHAR(255) NOT NULL,
    template         VARCHAR(100) NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',    -- pending|sent|failed
    sent_at          TIMESTAMPTZ,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_logs_merchant ON notification_logs(merchant_id);

-- ── ShedLock (distributed job locking) ───────────────────────────────────────
-- Required by ShedLock library to prevent concurrent job runs across instances.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
