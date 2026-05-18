CREATE TABLE users (
    id                              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id                       UUID        NOT NULL,
    email                           TEXT        NOT NULL,
    auth_provider                   TEXT        NOT NULL,
    auth_subject                    TEXT,
    password_hash                   TEXT,
    email_verified                  BOOLEAN     NOT NULL DEFAULT false,
    email_verification_token        TEXT,
    email_verification_token_exp    TIMESTAMPTZ,
    roles                           TEXT[]      NOT NULL DEFAULT '{}',
    metadata                        JSONB,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at                      TIMESTAMPTZ,
    CONSTRAINT uq_user_tenant_email UNIQUE (tenant_id, email)
);

CREATE INDEX ON users (tenant_id) WHERE deleted_at IS NULL;
CREATE INDEX ON users USING GIN (metadata) WHERE metadata IS NOT NULL;
CREATE INDEX ON users (email_verification_token)
  WHERE email_verification_token IS NOT NULL AND deleted_at IS NULL;

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
