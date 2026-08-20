-- Consumer-side idempotency ledger (docs/architecture/kafka-topics.md: "event_id — used
-- for consumer-side idempotency checks"). GraphNodeEventConsumer inserts one row per
-- successfully-applied envelope before mutating graph_nodes; a replayed envelope hits the
-- primary-key conflict below and is treated as a no-op.
CREATE TABLE processed_events (
    tenant_id    UUID        NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, event_id)
);

ALTER TABLE processed_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON processed_events
    USING (tenant_id = current_setting('app.current_tenant_id')::UUID);
