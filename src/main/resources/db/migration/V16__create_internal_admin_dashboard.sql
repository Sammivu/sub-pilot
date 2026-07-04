-- Internal Admin Dashboard — see docs/internal-admin-backend-spec.md
--
-- Admin bootstrapping is deliberately NOT done via any API endpoint (see
-- the spec's "one open question" — option 1, seed script, chosen for V1).
-- Insert the first super_admin row directly, e.g.:
--   INSERT INTO internal_admins (id, email, password_hash, role, display_name, created_at, updated_at)
--   VALUES ('...', 'you@subpilot.co', '<bcrypt hash>', 'super_admin', 'Platform Admin', now(), now());

CREATE TABLE internal_admins (
    id            VARCHAR(26)  NOT NULL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL, -- super_admin | ops_admin
    display_name  VARCHAR(255) NOT NULL,
    last_login_at TIMESTAMP,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);

-- Merchant operational status — active | under_review | suspended.
-- Existing merchants default to 'active' (they were already operating).
ALTER TABLE merchants ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';

-- Platform default fee policy, now DB-backed and admin-editable via
-- PATCH /v1/internal/fees/default — previously a fixed application.yml
-- value (subpilot.fees.default-bps / default-fixed-minor). Single-row
-- table by convention (id is always the literal string 'default'); yml
-- values remain the bootstrap fallback if this table is ever empty — see
-- PlatformFeePolicy.calculate.
CREATE TABLE platform_fee_default (
    id                  VARCHAR(20)  NOT NULL PRIMARY KEY DEFAULT 'default',
    fee_bps             INT          NOT NULL,
    fixed_fee_minor     BIGINT       NOT NULL,
    updated_by_admin_id VARCHAR(26),
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL
);

-- Deliberately separate from `audit_logs` (merchant-facing, requires a
-- non-null merchantId and actorType user|api_key — see AuditLog.java).
-- An internal admin action often has no single merchant (a platform fee
-- default change) and the actor is neither a merchant user nor an API key.
CREATE TABLE internal_audit_logs (
    id                VARCHAR(26)  NOT NULL PRIMARY KEY,
    actor_admin_id    VARCHAR(26)  NOT NULL,
    actor_email       VARCHAR(255) NOT NULL,
    target_type       VARCHAR(50)  NOT NULL, -- merchant | platform_fee_policy
    target_id         VARCHAR(255) NOT NULL,
    action_type       VARCHAR(100) NOT NULL,
    old_value         JSONB,
    new_value         JSONB,
    reason            VARCHAR(500) NOT NULL,
    created_at        TIMESTAMP    NOT NULL
);

CREATE INDEX idx_internal_audit_actor  ON internal_audit_logs(actor_admin_id);
CREATE INDEX idx_internal_audit_target ON internal_audit_logs(target_type, target_id);
CREATE INDEX idx_merchants_status      ON merchants(status);