# Activate Postgres RLS (security audit F7)
Status: **Done**, 2026-11-19. Merged in #235 (`feat/activate-rls`) and active on production (activate-rls-wizard.sh, stages 1–5).
Branch: `docs/security-plans` (plan only)  ·  Issue: security audit F7 (`docs/security-audit/`), no GitHub issue yet

## Context
Today, RLS does nothing:
- 21 tables have `ENABLE ROW LEVEL SECURITY` and a `tenant_isolation` policy, but no table has `FORCE`. The services connect as the owner of the tables, so the policies do not apply.
- No code sets `app.current_tenant_id`.
- 14 policies use `current_setting('app.current_tenant_id')` without `missing_ok`. If RLS applies, these policies raise an error when the setting is absent.
- `invitations` has `tenant_id` but no RLS.

Topology: one database `cartogra`, one schema for each service (`registry`, `topology`, `ingestion`, `contract`). The gateway uses the `registry` schema.

Goal: if a query on a user request path does not filter by `tenant_id`, Postgres returns only the rows of the current tenant.

## Decisions (review these)

### D1. Two roles
- `cartogra_owner`: owns the tables. Flyway uses it (`spring.flyway.user` / `spring.flyway.password`).
- `cartogra_app`: `NOSUPERUSER NOBYPASSRLS`, `USAGE` on the schemas, DML on the tables, `USAGE` on the sequences. The application DataSource uses it.
- Default privileges: `ALTER DEFAULT PRIVILEGES FOR ROLE cartogra_owner ... GRANT ... TO cartogra_app`. Then, new tables get the grants automatically.

### D2. System paths use a bypass flag, not a third role
Some code reads more than one tenant on purpose:
- Schedulers: `SyncScheduler`, `ClusterWorkerManager`, `StaleJobReaperScheduler`, `HealthProbeScheduler`, `DriftSweepScheduler`, the reapers, `DependencyGraphViewRefreshScheduler`, `RefreshTokenPurgeScheduler`, `TenantBootstrapScheduler`.
- Lookups before the tenant is known: login (`UserRepository.findByEmail`, `findAllByEmail`, token lookups), `RefreshTokenRepository.*`, `InvitationRepository.findByToken`, `ScmConnectionRepository.findByIdForWebhook`.

The policy becomes:
```sql
USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid
       OR current_setting('app.rls_bypass', true) = 'on')
```
The code sets `app.rls_bypass` only through `TenantDb.asSystem(() -> ...)`. A grep can find each call.

Why not a third role with `BYPASSRLS`: that needs a second DataSource and pool in each service, and each repository must select the correct DataSource. The threat is a missing filter in our code, not SQL injection, because all SQL uses bound parameters. The flag gives the same protection for less code.

Risk: SQL injection could set the flag. Today, the code has no string-concatenated SQL. If you want protection against SQL injection too, select a third role.

### D3. Set the tenant on the connection
- `TenantContext` (`shared/web`): a `ScopedValue<UUID>`. `InternalAuthFilter` binds it from `X-Tenant-Id`. Kafka consumers bind it from the `tenant_id` of the envelope.
- `TenantAwareDataSource` (`shared/web`): wraps Hikari. On `getConnection()`, it runs `SELECT set_config('app.current_tenant_id', ?, false), set_config('app.rls_bypass', ?, false)`. On `close()`, it runs `RESET ALL` before the connection goes back to the pool.
- A session-level setting (not `SET LOCAL`) is necessary. Reads that are not in a transaction use autocommit, and a `SET LOCAL` value is lost after each statement.
- Cost: one extra round trip for each connection checkout. The `@Transactional` methods check out a connection one time.
- No tenant and no bypass: the setting is NULL, and the query returns 0 rows. This is fail-closed.

### D4. Migrations (one for each service, next V number)
- `ALTER TABLE <t> FORCE ROW LEVEL SECURITY` on all tenant tables.
- `DROP POLICY tenant_isolation` and `CREATE POLICY tenant_isolation` again with the D2 expression and `missing_ok = true`, on all tenant tables. This also fixes the 14 policies without `missing_ok`.
- `invitations`: `ENABLE` + `FORCE` + the policy.
- The `GRANT`s for `cartogra_app` (D1).
- Tables without `tenant_id` (`billing_plans`, `flyway_schema_history`) do not get RLS.
- The materialized view `dependency_graph_view` (topology): RLS does not apply to materialized views. Reads already filter by `tenant_id`. Record this as a known gap.

### D5. Infrastructure
- Compose: an init script in `infra/docker-compose/postgres-init/` creates both roles. The services get `DB_USERNAME=cartogra_app`. Flyway gets `FLYWAY_USER=cartogra_owner`.
- Helm: the postgres chart creates the roles at init. The secrets of each service get `FLYWAY_USERNAME` / `FLYWAY_PASSWORD` in addition to the current keys. Update `infra/helm/cartogra/README.md`.
- Production host: the sealed secrets must be generated again with the new keys. You must do this step. I will write the commands.
- Existing databases: the init script does not run again on an existing volume. A migration cannot create a role with a password from a secret. Thus, run a one-time `bootstrap-roles.sql` with `psql` (a wizard step), then deploy.

### D6. Tests
- `PostgresTestSupport`: create both roles. The ITs connect as `cartogra_app`, and Flyway connects as the owner.
- New IT in each service: a query with no `WHERE tenant_id` that runs as `cartogra_app` with tenant A set returns only the rows of tenant A.
- New IT: each table with a `tenant_id` column has `relrowsecurity` and `relforcerowsecurity` (query `pg_class` and `information_schema.columns`). Then, a new table without RLS makes the build fail.
- Existing ITs that insert seed rows through `jdbc` as the app role must set the tenant or use the owner DataSource. Expect changes in many IT files.

## Steps
1. `shared/web`: `TenantContext`, `TenantAwareDataSource` (+ a `BeanPostProcessor` that wraps the `DataSource`), `TenantDb.asSystem`. Unit tests.
2. Bind `TenantContext` in `InternalAuthFilter` and in each `@KafkaListener` (contract 1, ingestion 1, registry 3, topology 2).
3. Wrap the system paths of D2 in `TenantDb.asSystem`.
4. Migrations of D4 for registry, topology, ingestion, contract.
5. `PostgresTestSupport` roles. Fix the existing ITs. Add the D6 ITs.
6. Compose init script and env. Helm chart and README. Write the `bootstrap-roles.sql` wizard.
7. Run all tests (needs Testcontainers: ask first). Verify on k3d `cartogra-local` (ask first).

## Acceptance criteria
- [x] A query without `WHERE tenant_id` that runs as the app role returns only the rows of the current tenant (an IT in each service).
- [x] All tables with `tenant_id` have RLS and FORCE (the IT of D6).
- [x] Flyway and the app use different roles. The app role has `NOSUPERUSER NOBYPASSRLS`.
- [x] Schedulers, login, refresh, invitations and webhooks continue to work (existing ITs pass).
- [x] `invitations` has RLS.

## Changes from the plan
- `spring.datasource.type: io.cartogra.web.tenant.TenantAwareDataSource` (a Hikari subclass) replaces the `BeanPostProcessor` of step 1.
- `spring.flyway.url` is set to the datasource URL. Without it, Boot cannot derive the Flyway datasource from `TenantAwareDataSource`.
- The policy uses `NULLIF(current_setting('app.current_tenant_id', true), '')`, because a connection without a tenant has an empty setting.
- No `RESET ALL` when a connection goes back to the pool: each checkout writes both settings again.
- The matview refresh runs through `refresh_dependency_graph_edges()` (`SECURITY DEFINER`). It sets `app.rls_bypass` in its body, because Postgres 16 does not let a non-superuser attach a custom setting to a function.
- `InternalTokenFilter` (plan 029, merged first) binds the tenant from the verified token, not `InternalAuthFilter` from the header.
- Test support: `TenantScopeExtension` runs the test code in system scope (`@NoTenantScope` turns it off), and it makes Awaitility poll on the test thread.
- Stage 4 of the wizard: merge first (the deploy starts by itself), then apply the secrets and restart the services.

## Out of scope
- RLS on `dependency_graph_view` (Postgres does not support it).
- A third role with `BYPASSRLS` (see D2).
