-- One API contract per live service; versions are immutable revisions numbered 1, 2, 3 per contract.
CREATE TABLE api_contracts (
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    service_id  UUID        NOT NULL,
    spec_type   TEXT        NOT NULL DEFAULT 'openapi' CHECK (spec_type = 'openapi'),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

CREATE UNIQUE INDEX api_contracts_live_service_uq ON api_contracts (tenant_id, service_id) WHERE deleted_at IS NULL;

ALTER TABLE api_contracts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_contracts
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);

CREATE TABLE contract_versions (
    id              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id       UUID        NOT NULL,
    contract_id     UUID        NOT NULL REFERENCES api_contracts (id),
    version_number  INT         NOT NULL CHECK (version_number > 0),
    label           TEXT,
    spec_raw        TEXT        NOT NULL,
    spec_canonical  TEXT        NOT NULL,
    spec_hash       CHAR(64)    NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    UNIQUE (contract_id, version_number)
);

CREATE INDEX ON contract_versions (contract_id, version_number DESC) WHERE deleted_at IS NULL;

ALTER TABLE contract_versions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON contract_versions
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);

-- Services Registry has deleted (service.deleted); a contract can no longer be created or versioned for them.
CREATE TABLE deleted_services (
    tenant_id   UUID        NOT NULL,
    service_id  UUID        NOT NULL,
    deleted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, service_id)
);

ALTER TABLE deleted_services ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON deleted_services
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
