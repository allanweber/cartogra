package io.cartogra.contract;

import io.cartogra.test.NoTenantScope;
import io.cartogra.test.RlsChecks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@NoTenantScope
class RowLevelSecurityIT extends AbstractContractIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theAppRoleIsSubjectToRls() {
        RlsChecks.assertAppRoleIsSubjectToRls(jdbc);
    }

    @Test
    void theAppRoleCanUseEveryTableButNotTheMigrationHistory() {
        RlsChecks.assertAppRoleHasDmlOnEveryTable(jdbc);
    }

    @Test
    void everyTenantTableForcesRls() {
        RlsChecks.assertEveryTenantTableForcesRls(jdbc);
    }

    @Test
    void aQueryWithoutATenantFilterSeesOnlyTheBoundTenant() {
        RlsChecks.assertIsolatesTenants(jdbc, "services",
                "INSERT INTO services (tenant_id, service_id, name) VALUES (?, gen_random_uuid(), 'rls')");
    }

    @Test
    void discoveredSpecsIsolatesTenants() {
        RlsChecks.assertIsolatesTenants(jdbc, "discovered_specs", """
                INSERT INTO discovered_specs (tenant_id, repository_full_name, file_path, content_sha256, spec_raw)
                VALUES (?, 'acme/rls', 'openapi.yaml', repeat('a', 64), 'openapi: 3.0.3')
                """);
    }

    @Test
    void inboundEdgesIsolatesTenants() {
        RlsChecks.assertIsolatesTenants(jdbc, "inbound_edges", """
                INSERT INTO inbound_edges (tenant_id, edge_id, source_service_id, target_service_id,
                                           dependency_type, protocol)
                VALUES (?, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'declared', 'http')
                """);
    }

    @Test
    void contractChecksIsolatesTenants() {
        RlsChecks.assertIsolatesTenants(jdbc, "contract_checks", """
                WITH t AS (SELECT ?::uuid AS tenant_id),
                c AS (INSERT INTO api_contracts (tenant_id, service_id)
                      SELECT tenant_id, gen_random_uuid() FROM t RETURNING id, tenant_id),
                v AS (INSERT INTO contract_versions (tenant_id, contract_id, version_number, spec_raw, spec_canonical,
                                                     spec_hash)
                      SELECT tenant_id, id, 1, '{}', '{}', repeat('a', 64) FROM c RETURNING id, tenant_id, contract_id)
                INSERT INTO contract_checks (tenant_id, service_id, contract_id, baseline_version_id, candidate_hash,
                                             is_breaking, changes, affected_consumer_ids, api_key_id)
                SELECT tenant_id, gen_random_uuid(), contract_id, id, repeat('a', 64), false, '[]'::jsonb,
                       '{}'::uuid[], gen_random_uuid()
                FROM v
                """);
    }
}
