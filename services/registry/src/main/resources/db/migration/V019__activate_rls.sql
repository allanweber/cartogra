-- Activates RLS: FORCE on each table with tenant_id (the policies then apply to the table owner too),
-- one policy expression on all of them, and DML grants for the application role cartogra_app.
-- The roles must exist before this migration runs: see infra/docker-compose/postgres-init and
-- infra/helm/cartogra/charts/postgres/files/bootstrap-roles.sql.

DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT c.table_name
        FROM information_schema.columns c
        JOIN information_schema.tables tb
          ON tb.table_schema = c.table_schema AND tb.table_name = c.table_name
        WHERE c.table_schema = current_schema()
          AND c.column_name = 'tenant_id'
          AND tb.table_type = 'BASE TABLE'
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING ('
            || 'tenant_id = NULLIF(current_setting(''app.current_tenant_id'', true), '''')::uuid'
            || ' OR current_setting(''app.rls_bypass'', true) = ''on'')', t);
    END LOOP;
END $$;

GRANT USAGE ON SCHEMA ${flyway:defaultSchema} TO cartogra_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA ${flyway:defaultSchema} TO cartogra_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA ${flyway:defaultSchema} TO cartogra_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cartogra_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA ${flyway:defaultSchema}
    GRANT USAGE, SELECT ON SEQUENCES TO cartogra_app;

-- The application must not change the migration history.
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT tablename FROM pg_tables
        WHERE schemaname = current_schema() AND tablename LIKE 'flyway_schema_history%'
    LOOP
        EXECUTE format('REVOKE ALL ON %I FROM cartogra_app', t);
    END LOOP;
END $$;
