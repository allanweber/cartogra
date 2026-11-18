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
    void everyTenantTableForcesRls() {
        RlsChecks.assertEveryTenantTableForcesRls(jdbc);
    }

    @Test
    void aQueryWithoutATenantFilterSeesOnlyTheBoundTenant() {
        RlsChecks.assertIsolatesTenants(jdbc, "teams",
                "INSERT INTO teams (tenant_id, name) VALUES (?, 'rls-' || gen_random_uuid())");
    }
}
