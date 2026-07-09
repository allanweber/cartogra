package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.domain.PlanLimits;
import io.cartogra.registry.repository.PlanLimitRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.NoSuchElementException;
import java.util.UUID;

@Repository
public class JdbcPlanLimitRepository implements PlanLimitRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPlanLimitRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PlanLimits findByTenantId(UUID tenantId) {
        String sql = """
            SELECT bp.max_services, bp.max_scm_connections, bp.max_k8s_clusters
            FROM tenants t
            JOIN billing_plans bp ON bp.id = t.plan_id
            WHERE t.tenant_id = :tenantId AND t.deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        return jdbc.query(sql, params, (rs, _) -> new PlanLimits(
            rs.getInt("max_services"),
            rs.getInt("max_scm_connections"),
            rs.getInt("max_k8s_clusters")
        )).stream().findFirst()
            .orElseThrow(() -> new NoSuchElementException("No billing plan found for tenant " + tenantId));
    }
}
