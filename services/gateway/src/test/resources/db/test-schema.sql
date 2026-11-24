-- Test schema for gateway integration tests.
-- Mirrors the registry Flyway migrations (V001, V002, V003, V008, V009, V010, V011, V012, V013, V014, V015, V016, V017, V020).
-- Used instead of Flyway since the gateway has no migrations of its own. AbstractGatewayIT runs it
-- with psql as the superuser; the application connects as cartogra_app.

CREATE TABLE IF NOT EXISTS billing_plans (
    id                    UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name                  TEXT        NOT NULL,
    slug                  TEXT        NOT NULL UNIQUE,
    max_services          INT         NOT NULL,
    max_users             INT         NOT NULL,
    max_api_keys          INT         NOT NULL,
    max_scm_connections   INT         NOT NULL,
    max_k8s_clusters      INT         NOT NULL,
    max_teams             INT         NOT NULL DEFAULT -1,
    sso_enabled           BOOLEAN     NOT NULL,
    rate_limit_replenish  INT         NOT NULL,
    rate_limit_burst      INT         NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);

INSERT INTO billing_plans
    (name, slug, max_services, max_users, max_api_keys, max_scm_connections, max_k8s_clusters, max_teams, sso_enabled, rate_limit_replenish, rate_limit_burst)
VALUES
    ('Free',       'free',       10,  3,  2,  1, 0, 2,  false, 20,  40),
    ('Business',   'business',   100, 25, 20, 5, 3, 25, true,  60,  120),
    ('Enterprise', 'enterprise', -1,  -1, -1, -1, -1, -1, true, 200, 400)
ON CONFLICT (slug) DO NOTHING;

CREATE TABLE IF NOT EXISTS tenants (
    id          UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID          NOT NULL,
    name        VARCHAR(255)  NOT NULL,
    slug        VARCHAR(255)  NOT NULL UNIQUE,
    plan_id     UUID          NOT NULL REFERENCES billing_plans(id),
    metadata    JSONB,
    public_sandbox BOOLEAN    NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS teams (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_team_tenant_name UNIQUE (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS users (
    id                              UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id                       UUID          NOT NULL,
    email                           VARCHAR(254)  NOT NULL,
    name                            VARCHAR(255),
    auth_provider                   VARCHAR(50)   NOT NULL,
    auth_subject                    VARCHAR(255),
    password_hash                   VARCHAR(72),
    email_verified                  BOOLEAN       NOT NULL DEFAULT false,
    email_verification_token        VARCHAR(10),
    email_verification_token_exp    TIMESTAMPTZ,
    password_reset_token            VARCHAR(10),
    password_reset_token_exp        TIMESTAMPTZ,
    roles                           VARCHAR(50)[] NOT NULL DEFAULT '{}',
    metadata                        JSONB,
    created_at                      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at                      TIMESTAMPTZ,
    disabled_at                     TIMESTAMPTZ,
    CONSTRAINT uq_user_tenant_email UNIQUE (tenant_id, email)
);

CREATE TABLE IF NOT EXISTS team_members (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    team_id     UUID        NOT NULL REFERENCES teams(id),
    user_id     UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_member UNIQUE (team_id, user_id)
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    user_id     UUID         NOT NULL REFERENCES users(id),
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    previous_hash VARCHAR(64),
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS invitations (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    email       VARCHAR(254) NOT NULL,
    role        VARCHAR(50)  NOT NULL,
    invited_by  UUID         NOT NULL REFERENCES users(id),
    team_id     UUID         REFERENCES teams(id),
    token       VARCHAR(64)  NOT NULL UNIQUE,
    token_exp   TIMESTAMPTZ  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS tenant_oidc_configs (
    id              UUID          NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       UUID          NOT NULL UNIQUE,
    discovery_uri   VARCHAR(2048) NOT NULL,
    client_id       VARCHAR(255)  NOT NULL,
    client_secret   VARCHAR(512)  NOT NULL,
    enabled         BOOLEAN       NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS audit_events (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    event_id    UUID        NOT NULL UNIQUE,
    tenant_id   UUID        NOT NULL,
    entity_type TEXT        NOT NULL,
    entity_id   UUID        NOT NULL,
    action      TEXT        NOT NULL,
    actor_kind  TEXT        NOT NULL,
    actor_id    UUID,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS api_keys (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    name         TEXT        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    key_hash     CHAR(64)    NOT NULL UNIQUE,
    key_prefix   TEXT        NOT NULL,
    scopes       TEXT[]      NOT NULL CHECK (cardinality(scopes) > 0),
    expires_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_by   UUID        NOT NULL REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS api_keys_tenant_active_idx ON api_keys (tenant_id)
    WHERE revoked_at IS NULL AND deleted_at IS NULL;

-- RLS as in registry V019: the ITs connect as cartogra_app, so the policies apply.
GRANT USAGE ON SCHEMA public TO cartogra_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO cartogra_app;
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenants;
CREATE POLICY tenant_isolation ON tenants USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
ALTER TABLE teams ENABLE ROW LEVEL SECURITY;
ALTER TABLE teams FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON teams;
CREATE POLICY tenant_isolation ON teams USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE users FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON users;
CREATE POLICY tenant_isolation ON users USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
ALTER TABLE team_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE team_members FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON team_members;
CREATE POLICY tenant_isolation ON team_members USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON refresh_tokens;
CREATE POLICY tenant_isolation ON refresh_tokens USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON invitations;
CREATE POLICY tenant_isolation ON invitations USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
ALTER TABLE tenant_oidc_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_oidc_configs FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON tenant_oidc_configs;
CREATE POLICY tenant_isolation ON tenant_oidc_configs USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON audit_events;
CREATE POLICY tenant_isolation ON audit_events USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
ALTER TABLE api_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON api_keys;
CREATE POLICY tenant_isolation ON api_keys USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid OR current_setting('app.rls_bypass', true) = 'on');
