CREATE TABLE team_members (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    team_id     UUID        NOT NULL REFERENCES teams(id),
    user_id     UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_team_member UNIQUE (team_id, user_id)
);

CREATE INDEX ON team_members (team_id);
CREATE INDEX ON team_members (tenant_id, user_id);

ALTER TABLE team_members ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON team_members
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
