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
}
