package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.repository.TenantTopologySettingsRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTenantTopologySettingsRepository implements TenantTopologySettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTenantTopologySettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Integer> findSpofThreshold(UUID tenantId) {
        String sql = "SELECT spof_fan_in_threshold FROM tenant_topology_settings WHERE tenant_id = :tenantId";
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        List<Integer> results = jdbc.query(sql, params, (rs, _) -> rs.getInt("spof_fan_in_threshold"));
        return results.stream().findFirst();
    }
}
