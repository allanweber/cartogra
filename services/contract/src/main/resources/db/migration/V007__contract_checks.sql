-- Results of CI checks: a candidate spec compared with the baseline, never stored as a version.
CREATE TABLE contract_checks (
    id                    UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id             UUID        NOT NULL,
    service_id            UUID        NOT NULL,
    contract_id           UUID        NOT NULL REFERENCES api_contracts (id),
    baseline_version_id   UUID        NOT NULL REFERENCES contract_versions (id),
    candidate_hash        CHAR(64)    NOT NULL,
    is_breaking           BOOLEAN     NOT NULL,
    changes               JSONB       NOT NULL,
    affected_consumer_ids UUID[]      NOT NULL,
    api_key_id            UUID        NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ON contract_checks (tenant_id, service_id, created_at DESC);

ALTER TABLE contract_checks ENABLE ROW LEVEL SECURITY;
ALTER TABLE contract_checks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contract_checks USING (
    tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid
    OR current_setting('app.rls_bypass', true) = 'on');

CREATE INDEX ON services (tenant_id, lower(name)) WHERE deleted_at IS NULL;
