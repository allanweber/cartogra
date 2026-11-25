-- Gives the application role its rights on the tables that cartogra_owner created after the RLS migration.
-- The RLS migration set the default privileges for the role that ran it. In production, that role was the
-- superuser, so the later tables of cartogra_owner got no rights, and cartogra_app got "permission denied".
-- Flyway runs this migration as cartogra_owner, so the default privileges below apply to its next tables too.

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
