package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.application.dto.ServiceFilter;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcServiceRepository implements ServiceRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcServiceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Service> findById(UUID tenantId, UUID id) {
        String sql = """
                SELECT * FROM services
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id);
        return jdbc.query(sql, params, SERVICE_MAPPER).stream().findFirst();
    }

    @Override
    public List<Service> findAll(UUID tenantId, ServiceFilter filter, int limit, int offset) {
        var sql = new StringBuilder("""
                SELECT * FROM services
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                """);
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        applyFilter(sql, params, filter);
        sql.append(" ORDER BY name ASC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(sql.toString(), params, SERVICE_MAPPER);
    }

    @Override
    public long count(UUID tenantId, ServiceFilter filter) {
        var sql = new StringBuilder("""
                SELECT COUNT(*) FROM services
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                """);
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        applyFilter(sql, params, filter);
        Long result = jdbc.queryForObject(sql.toString(), params, Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public List<Service> findOrphaned(UUID tenantId, int limit, int offset) {
        String sql = """
                SELECT s.* FROM services s
                WHERE s.tenant_id = :tenantId AND s.deleted_at IS NULL
                  AND (s.team_id IS NULL
                       OR NOT EXISTS (
                           SELECT 1 FROM teams t
                           WHERE t.id = s.team_id AND t.deleted_at IS NULL
                       ))
                ORDER BY s.name ASC LIMIT :limit OFFSET :offset
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, SERVICE_MAPPER);
    }

    @Override
    public Service save(Service service) {
        String sql = """
                INSERT INTO services (
                    id, tenant_id, name, description, team_id, repository_url,
                    tech_stack, metadata, health_status, last_deployed_at,
                    created_at, updated_at, deleted_at
                ) VALUES (
                    :id, :tenantId, :name, :description, :teamId, :repositoryUrl,
                    :techStack, CAST(:metadata AS JSONB), :healthStatus, :lastDeployedAt,
                    :createdAt, :updatedAt, :deletedAt
                )
                ON CONFLICT (id) DO UPDATE SET
                    name            = EXCLUDED.name,
                    description     = EXCLUDED.description,
                    team_id         = EXCLUDED.team_id,
                    repository_url  = EXCLUDED.repository_url,
                    tech_stack      = EXCLUDED.tech_stack,
                    metadata        = EXCLUDED.metadata,
                    health_status   = EXCLUDED.health_status,
                    last_deployed_at = EXCLUDED.last_deployed_at,
                    updated_at      = EXCLUDED.updated_at,
                    deleted_at      = EXCLUDED.deleted_at
                RETURNING *
                """;
        return jdbc.queryForObject(sql, toParams(service), SERVICE_MAPPER);
    }

    @Override
    public void softDelete(UUID tenantId, UUID id) {
        String sql = """
                UPDATE services SET deleted_at = now(), updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id));
    }

    @Override
    public Optional<Service> findByRepositoryPath(UUID tenantId, String repositoryPath) {
        String sql = """
                SELECT * FROM services
                WHERE tenant_id = :tenantId
                  AND repository_url LIKE '%' || :repositoryPath
                  AND deleted_at IS NULL
                LIMIT 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("repositoryPath", repositoryPath);
        return jdbc.query(sql, params, SERVICE_MAPPER).stream().findFirst();
    }

    @Override
    public boolean existsByName(UUID tenantId, String name, UUID excludeId) {
        String sql = """
                SELECT COUNT(*) FROM services
                WHERE tenant_id = :tenantId AND lower(name) = lower(:name)
                  AND deleted_at IS NULL AND (CAST(:excludeId AS UUID) IS NULL OR id != :excludeId)
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("name", name)
                .addValue("excludeId", excludeId);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count != null && count > 0;
    }

    private void applyFilter(StringBuilder sql, MapSqlParameterSource params, ServiceFilter filter) {
        if (filter.teamId() != null) {
            sql.append(" AND team_id = :teamId");
            params.addValue("teamId", filter.teamId());
        }
        if (filter.healthStatus() != null) {
            sql.append(" AND health_status = :healthStatus");
            params.addValue("healthStatus", filter.healthStatus());
        }
        if (filter.techStackQuery() != null) {
            sql.append(" AND tech_stack::text ILIKE :techStackQuery");
            params.addValue("techStackQuery", "%" + filter.techStackQuery() + "%");
        }
        if (filter.search() != null && !filter.search().isBlank()) {
            sql.append(" AND to_tsvector('english', coalesce(name,'') || ' ' || coalesce(description,'')) @@ plainto_tsquery('english', :search)");
            params.addValue("search", filter.search());
        }
    }

    private MapSqlParameterSource toParams(Service s) {
        return new MapSqlParameterSource()
                .addValue("id", s.id())
                .addValue("tenantId", s.tenantId())
                .addValue("name", s.name())
                .addValue("description", s.description())
                .addValue("teamId", s.teamId())
                .addValue("repositoryUrl", s.repositoryUrl())
                .addValue("techStack", s.techStack())
                .addValue("metadata", s.metadata())
                .addValue("healthStatus", s.healthStatus().toDbValue())
                .addValue("lastDeployedAt", s.lastDeployedAt() != null ? java.sql.Timestamp.from(s.lastDeployedAt()) : null)
                .addValue("createdAt", java.sql.Timestamp.from(s.createdAt()))
                .addValue("updatedAt", java.sql.Timestamp.from(s.updatedAt()))
                .addValue("deletedAt", s.deletedAt() != null ? java.sql.Timestamp.from(s.deletedAt()) : null);
    }

    private static final RowMapper<Service> SERVICE_MAPPER = (rs, _) -> mapService(rs);

    private static Service mapService(ResultSet rs) throws SQLException {
        return new Service(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("team_id") != null ? UUID.fromString(rs.getString("team_id")) : null,
                rs.getString("repository_url"),
                rs.getString("tech_stack"),
                rs.getString("metadata"),
                ServiceHealthStatus.fromString(rs.getString("health_status")),
                rs.getTimestamp("last_deployed_at") != null ? rs.getTimestamp("last_deployed_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null
        );
    }
}
