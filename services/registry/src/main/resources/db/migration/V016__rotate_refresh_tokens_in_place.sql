ALTER TABLE refresh_tokens
    ADD COLUMN previous_hash VARCHAR(64),
    ADD COLUMN last_used_at  TIMESTAMPTZ;

CREATE INDEX ON refresh_tokens (previous_hash)
    WHERE previous_hash IS NOT NULL AND revoked_at IS NULL AND deleted_at IS NULL;
