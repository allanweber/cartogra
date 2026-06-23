package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.registry.domain.Team;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTeamRepository implements TeamRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTeamRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Team> findById(UUID tenantId, UUID id) {
        String sql = """
                SELECT * FROM teams
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id);
        return jdbc.query(sql, params, TEAM_MAPPER).stream().findFirst();
    }

    @Override
    public List<Team> findAll(UUID tenantId, int limit, int offset) {
        String sql = """
                SELECT * FROM teams
                WHERE tenant_id = :tenantId AND deleted_at IS NULL
                ORDER BY name ASC LIMIT :limit OFFSET :offset
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query(sql, params, TEAM_MAPPER);
    }

    @Override
    public long count(UUID tenantId) {
        String sql = "SELECT COUNT(*) FROM teams WHERE tenant_id = :tenantId AND deleted_at IS NULL";
        Long result = jdbc.queryForObject(sql, new MapSqlParameterSource("tenantId", tenantId), Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public Team save(Team team) {
        String sql = """
                INSERT INTO teams (id, tenant_id, name, created_at, updated_at, deleted_at)
                VALUES (:id, :tenantId, :name, :createdAt, :updatedAt, :deletedAt)
                ON CONFLICT (id) DO UPDATE SET
                    name       = EXCLUDED.name,
                    updated_at = EXCLUDED.updated_at,
                    deleted_at = EXCLUDED.deleted_at
                RETURNING *
                """;
        return jdbc.queryForObject(sql, toParams(team), TEAM_MAPPER);
    }

    @Override
    public void softDelete(UUID tenantId, UUID id) {
        String sql = """
                UPDATE teams SET deleted_at = now(), updated_at = now()
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id));
    }

    @Override
    public Optional<Team> findByTenantAndName(UUID tenantId, String name) {
        String sql = """
                SELECT * FROM teams
                WHERE tenant_id = :tenantId AND lower(name) = lower(:name) AND deleted_at IS NULL
                LIMIT 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("name", name);
        return jdbc.query(sql, params, TEAM_MAPPER).stream().findFirst();
    }

    @Override
    public boolean existsByName(UUID tenantId, String name, UUID excludeId) {
        String sql = """
                SELECT COUNT(*) FROM teams
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

    private MapSqlParameterSource toParams(Team t) {
        return new MapSqlParameterSource()
                .addValue("id", t.id())
                .addValue("tenantId", t.tenantId())
                .addValue("name", t.name())
                .addValue("createdAt", java.sql.Timestamp.from(t.createdAt()))
                .addValue("updatedAt", java.sql.Timestamp.from(t.updatedAt()))
                .addValue("deletedAt", t.deletedAt() != null ? java.sql.Timestamp.from(t.deletedAt()) : null);
    }

    private static final RowMapper<Team> TEAM_MAPPER = (rs, _) -> new Team(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("name"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null
    );
}
