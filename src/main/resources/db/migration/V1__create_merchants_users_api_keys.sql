-- V1: Merchant tenants, users, and API keys
-- Every table that holds merchant data will have merchant_id as a FK to this table.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Merchants (one row = one tenant) ────────────────────────────────────────
CREATE TABLE merchants (
    id                      VARCHAR(26)  PRIMARY KEY,            -- ULID
    business_name           VARCHAR(255) NOT NULL,
    email                   VARCHAR(255) NOT NULL UNIQUE,
    password_hash           VARCHAR(255) NOT NULL,
    slug                    VARCHAR(100) NOT NULL UNIQUE,         -- used in hosted plan URLs
    webhook_signing_secret  VARCHAR(255) NOT NULL,               -- HMAC secret for outbound webhooks
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_merchants_email ON merchants(email);
CREATE INDEX idx_merchants_slug  ON merchants(slug);

-- ── Users (operator logins — scoped to a merchant) ──────────────────────────
CREATE TABLE users (
    id            VARCHAR(26)  PRIMARY KEY,
    merchant_id   VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    email         VARCHAR(255) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    role          VARCHAR(50)  NOT NULL DEFAULT 'owner',         -- owner | admin | viewer
    password_hash VARCHAR(255) NOT NULL,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE(merchant_id, email)
);

CREATE INDEX idx_users_merchant_id ON users(merchant_id);
CREATE INDEX idx_users_email       ON users(email);

-- ── API Keys ─────────────────────────────────────────────────────────────────
-- Raw key shown ONCE at creation. We store only the SHA-256 hash here.
CREATE TABLE api_keys (
    id           VARCHAR(26)  PRIMARY KEY,
    merchant_id  VARCHAR(26)  NOT NULL REFERENCES merchants(id),
    key_hash     VARCHAR(255) NOT NULL UNIQUE,                   -- SHA-256 of the raw key
    prefix       VARCHAR(20)  NOT NULL,                          -- e.g. "sk_live_abc123" (first 12 chars, safe to display)
    label        VARCHAR(255),
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_api_keys_merchant_id ON api_keys(merchant_id);
CREATE INDEX idx_api_keys_key_hash    ON api_keys(key_hash);
