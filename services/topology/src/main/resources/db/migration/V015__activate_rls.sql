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

-- REFRESH MATERIALIZED VIEW needs the owner of the view, and cartogra_app is not the owner. This function
-- runs as its owner. It sets app.rls_bypass itself: a refresh that sees only one tenant would remove the
-- rows of all other tenants from the view.
-- The bypass is set in the body: Postgres does not let a non-superuser attach a custom setting to a function.
CREATE FUNCTION refresh_dependency_graph_edges() RETURNS void
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = ${flyway:defaultSchema}, pg_temp
AS $$
DECLARE
    previous text := current_setting('app.rls_bypass', true);
BEGIN
    PERFORM set_config('app.rls_bypass', 'on', true);
    REFRESH MATERIALIZED VIEW CONCURRENTLY dependency_graph_edges;
    PERFORM set_config('app.rls_bypass', coalesce(previous, ''), true);
END $$;

REVOKE ALL ON FUNCTION refresh_dependency_graph_edges() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION refresh_dependency_graph_edges() TO cartogra_app;
GRANT SELECT ON dependency_graph_edges TO cartogra_app;
