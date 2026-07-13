package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.repository.TeamRepository;
import io.cartogra.registry.domain.Team;
import io.cartogra.registry.domain.TeamMember;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    @Override
    public boolean isMember(UUID tenantId, UUID teamId, UUID userId) {
        String sql = """
                SELECT COUNT(*) FROM team_members
                WHERE tenant_id = :tenantId AND team_id = :teamId AND user_id = :userId
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("teamId", teamId)
                .addValue("userId", userId);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count != null && count > 0;
    }

    @Override
    public Set<UUID> findTeamIdsByMember(UUID tenantId, UUID userId) {
        String sql = """
                SELECT team_id FROM team_members
                WHERE tenant_id = :tenantId AND user_id = :userId
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId);
        return Set.copyOf(jdbc.queryForList(sql, params, UUID.class));
    }

    @Override
    public List<TeamMember> findMembers(UUID tenantId, UUID teamId) {
        String sql = """
                SELECT * FROM team_members
                WHERE tenant_id = :tenantId AND team_id = :teamId
                ORDER BY created_at ASC
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("teamId", teamId);
        return jdbc.query(sql, params, TEAM_MEMBER_MAPPER);
    }

    @Override
    public TeamMember addMember(TeamMember member) {
        String sql = """
                INSERT INTO team_members (id, tenant_id, team_id, user_id, created_at)
                VALUES (:id, :tenantId, :teamId, :userId, :createdAt)
                RETURNING *
                """;
        var params = new MapSqlParameterSource()
                .addValue("id", member.id())
                .addValue("tenantId", member.tenantId())
                .addValue("teamId", member.teamId())
                .addValue("userId", member.userId())
                .addValue("createdAt", java.sql.Timestamp.from(member.createdAt()));
        return jdbc.queryForObject(sql, params, TEAM_MEMBER_MAPPER);
    }

    @Override
    public void removeMember(UUID tenantId, UUID teamId, UUID userId) {
        String sql = """
                DELETE FROM team_members
                WHERE tenant_id = :tenantId AND team_id = :teamId AND user_id = :userId
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("teamId", teamId)
                .addValue("userId", userId));
    }

    private static final RowMapper<TeamMember> TEAM_MEMBER_MAPPER = (rs, _) -> new TeamMember(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            UUID.fromString(rs.getString("team_id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getTimestamp("created_at").toInstant()
    );

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
