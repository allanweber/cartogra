-- Creates the two database roles of Cartogra and gives the service schemas to the owner role.
-- Run it as a superuser with psql. It is idempotent: you can run it again, also to change a password.
--
--   psql -v ON_ERROR_STOP=1 -v owner_password=... -v app_password=... -d cartogra -f bootstrap-roles.sql
--
-- cartogra_owner: owns the schemas and tables. Flyway connects with it.
-- cartogra_app:   the application connects with it. It is not the owner and has NOBYPASSRLS, so the
--                 tenant_isolation policies always apply to it.

\set ON_ERROR_STOP on

SELECT format('CREATE ROLE cartogra_owner LOGIN PASSWORD %L', :'owner_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cartogra_owner') \gexec
SELECT format('CREATE ROLE cartogra_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cartogra_app') \gexec

ALTER ROLE cartogra_owner WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'owner_password';
ALTER ROLE cartogra_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD :'app_password';

SELECT format('GRANT CONNECT, CREATE ON DATABASE %I TO cartogra_owner', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO cartogra_app', current_database()) \gexec

-- Existing databases: the schemas were created by the superuser. Give each schema and its objects to
-- cartogra_owner, so Flyway (as cartogra_owner) can run the next migrations. A new database has no
-- schemas yet: Flyway creates them as cartogra_owner.
DO $$
DECLARE
    s text;
    r record;
BEGIN
    FOREACH s IN ARRAY ARRAY['registry', 'topology', 'ingestion', 'contract'] LOOP
        CONTINUE WHEN NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = s);
        EXECUTE format('ALTER SCHEMA %I OWNER TO cartogra_owner', s);
        FOR r IN
            SELECT c.relname, c.relkind
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = s
              AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
              AND NOT (c.relkind = 'S' AND EXISTS (
                  SELECT 1 FROM pg_depend d WHERE d.objid = c.oid AND d.deptype IN ('a', 'i')))
        LOOP
            EXECUTE format('ALTER %s %I.%I OWNER TO cartogra_owner',
                CASE r.relkind
                    WHEN 'v' THEN 'VIEW'
                    WHEN 'm' THEN 'MATERIALIZED VIEW'
                    WHEN 'S' THEN 'SEQUENCE'
                    ELSE 'TABLE'
                END, s, r.relname);
        END LOOP;
        FOR r IN
            SELECT p.oid::regprocedure AS signature
            FROM pg_proc p
            JOIN pg_namespace n ON n.oid = p.pronamespace
            WHERE n.nspname = s
        LOOP
            EXECUTE format('ALTER ROUTINE %s OWNER TO cartogra_owner', r.signature);
        END LOOP;
    END LOOP;
END $$;
