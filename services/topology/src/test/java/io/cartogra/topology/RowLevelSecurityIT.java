package io.cartogra.topology;

import static org.assertj.core.api.Assertions.assertThat;

import io.cartogra.test.NoTenantScope;
import io.cartogra.test.RlsChecks;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.web.tenant.TenantDb;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@NoTenantScope
class RowLevelSecurityIT extends AbstractTopologyIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DependencyGraphViewRepository graphViewRepository;

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
        RlsChecks.assertIsolatesTenants(jdbc, "tenant_topology_settings",
                "INSERT INTO tenant_topology_settings (tenant_id) VALUES (?)");
    }

    @Test
    void theAppRoleRefreshesTheGraphViewForAllTenantsWithoutAScope() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantDb.runAsSystem(() -> {
            insertEdge(tenantA);
            insertEdge(tenantB);
        });

        graphViewRepository.refresh();

        List<UUID> tenants = jdbc.queryForList(
                "SELECT DISTINCT tenant_id FROM dependency_graph_edges WHERE tenant_id IN (?, ?)",
                UUID.class, tenantA, tenantB);
        assertThat(tenants).containsExactlyInAnyOrder(tenantA, tenantB);
    }

    private void insertEdge(UUID tenantId) {
        jdbc.update("""
                INSERT INTO dependencies (tenant_id, source_service_id, target_service_id, dependency_type, protocol)
                VALUES (?, gen_random_uuid(), gen_random_uuid(), 'declared', 'http')
                """, tenantId);
    }
}
