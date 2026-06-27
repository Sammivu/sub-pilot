ALTER TABLE api_keys
ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE api_keys
SET updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE api_keys
ALTER COLUMN updated_at SET NOT NULL;