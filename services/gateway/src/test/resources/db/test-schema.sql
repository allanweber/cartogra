-- Test schema for gateway integration tests.
-- Mirrors the registry Flyway migrations (V001, V002, V003, V008, V009, V010, V011, V012, V013, V014).
-- Used instead of Flyway since the gateway has no migrations of its own.

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
    issued_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ  NOT NULL,
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
