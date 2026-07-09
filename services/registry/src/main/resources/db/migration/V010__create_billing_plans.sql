CREATE TABLE billing_plans (
    id                    UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    name                  TEXT        NOT NULL,
    slug                  TEXT        NOT NULL UNIQUE,
    max_services          INT         NOT NULL,
    max_users             INT         NOT NULL,
    max_api_keys          INT         NOT NULL,
    max_scm_connections   INT         NOT NULL,
    max_k8s_clusters      INT         NOT NULL,
    sso_enabled           BOOLEAN     NOT NULL,
    rate_limit_replenish  INT         NOT NULL,
    rate_limit_burst      INT         NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);

-- -1 in a max_* column means unlimited (columns are NOT NULL, so -1 is used instead of NULL)
INSERT INTO billing_plans
    (name, slug, max_services, max_users, max_api_keys, max_scm_connections, max_k8s_clusters, sso_enabled, rate_limit_replenish, rate_limit_burst)
VALUES
    ('Free',       'free',       10,  3,  2,  1, 0, false, 20,  40),
    ('Business',   'business',   100, 25, 20, 5, 3, true,  60,  120),
    ('Enterprise', 'enterprise', -1,  -1, -1, -1, -1, true, 200, 400);
