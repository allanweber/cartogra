CREATE TABLE k8s_clusters (
    id               UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id        UUID         NOT NULL,
    name             TEXT         NOT NULL,
    source           VARCHAR(20)  NOT NULL,
    api_server_url   TEXT         NOT NULL,
    ca_cert_pem      TEXT,
    sa_token         TEXT,
    skip_tls_verify  BOOLEAN      NOT NULL DEFAULT false,
    status           VARCHAR(20)  NOT NULL DEFAULT 'CONNECTING',
    status_message   TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ
);

CREATE INDEX ON k8s_clusters (tenant_id) WHERE deleted_at IS NULL;

ALTER TABLE k8s_clusters ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON k8s_clusters
    USING (tenant_id = current_setting('app.current_tenant_id', true)::UUID);
