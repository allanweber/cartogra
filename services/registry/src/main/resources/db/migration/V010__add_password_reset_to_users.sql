ALTER TABLE users
    ADD COLUMN password_reset_token     TEXT,
    ADD COLUMN password_reset_token_exp TIMESTAMPTZ;

CREATE INDEX ON users (password_reset_token)
  WHERE password_reset_token IS NOT NULL AND deleted_at IS NULL;
