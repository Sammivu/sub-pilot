-- V12: Refresh token support (Backend Gap 4)
--
-- Adds a refresh_token_hash column to users so a long-lived refresh token
-- can be issued alongside the short-lived JWT access token, without storing
-- the raw refresh token anywhere — only its SHA-256 hash, same convention
-- as api_keys.key_hash.
--
-- refresh_token_expires_at lets AuthService reject an expired refresh token
-- without needing to decode anything — it's just looked up and compared.

ALTER TABLE users
    ADD COLUMN refresh_token_hash VARCHAR(255),
    ADD COLUMN refresh_token_expires_at TIMESTAMPTZ;

CREATE INDEX idx_users_refresh_token_hash ON users(refresh_token_hash);