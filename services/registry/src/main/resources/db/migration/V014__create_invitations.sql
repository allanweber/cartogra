CREATE TABLE invitations (
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

CREATE INDEX ON invitations (tenant_id);
CREATE INDEX ON invitations (token) WHERE status = 'PENDING';
