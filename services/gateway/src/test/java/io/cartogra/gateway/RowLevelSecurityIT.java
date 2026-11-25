package io.cartogra.gateway;

import io.cartogra.test.NoTenantScope;
import io.cartogra.test.RlsChecks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@NoTenantScope
class RowLevelSecurityIT extends AbstractGatewayIT {

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
        RlsChecks.assertIsolatesTenants(jdbc, "teams",
                "INSERT INTO teams (tenant_id, name) VALUES (?, 'rls-' || gen_random_uuid())");
    }

    @Test
    void aKeyQueryWithoutATenantFilterSeesOnlyTheBoundTenant() {
        java.util.UUID creator = java.util.UUID.randomUUID();
        new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .update("INSERT INTO users (id, tenant_id, email, auth_provider) VALUES (?, ?, ?, 'local')",
                        creator, java.util.UUID.randomUUID(), creator + "@test.com");
        RlsChecks.assertIsolatesTenants(jdbc, "api_keys",
                "INSERT INTO api_keys (tenant_id, name, key_hash, key_prefix, scopes, created_by) VALUES (?, 'rls',"
                        + " md5(random()::text) || md5(random()::text), 'ctg_rls', ARRAY['ci:check'], '" + creator + "')");
    }
}
