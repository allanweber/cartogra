package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.domain.BillingPlan;
import io.cartogra.gateway.repository.BillingPlanRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcBillingPlanRepository implements BillingPlanRepository {

    private static final String SELECT_COLUMNS = """
        id, name, slug, max_services, max_users, max_api_keys, max_scm_connections,
        max_k8s_clusters, sso_enabled, rate_limit_replenish, rate_limit_burst, created_at, updated_at
        """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBillingPlanRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BillingPlan> findBySlug(String slug) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM billing_plans WHERE slug = :slug AND deleted_at IS NULL";
        var params = new MapSqlParameterSource().addValue("slug", slug);
        return jdbc.query(sql, params, JdbcBillingPlanRepository::mapRow).stream().findFirst();
    }

    @Override
    public List<BillingPlan> findAll() {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM billing_plans WHERE deleted_at IS NULL ORDER BY max_services";
        return jdbc.query(sql, JdbcBillingPlanRepository::mapRow);
    }

    private static BillingPlan mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BillingPlan(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getInt("max_services"),
            rs.getInt("max_users"),
            rs.getInt("max_api_keys"),
            rs.getInt("max_scm_connections"),
            rs.getInt("max_k8s_clusters"),
            rs.getBoolean("sso_enabled"),
            rs.getInt("rate_limit_replenish"),
            rs.getInt("rate_limit_burst"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            null
        );
    }
}
