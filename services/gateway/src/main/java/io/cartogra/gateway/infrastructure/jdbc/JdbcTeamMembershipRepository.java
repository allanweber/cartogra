package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.repository.TeamMembershipRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcTeamMembershipRepository implements TeamMembershipRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTeamMembershipRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean teamExists(UUID tenantId, UUID teamId) {
        String sql = """
                SELECT COUNT(*) FROM teams
                WHERE tenant_id = :tenantId AND id = :teamId AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("teamId", teamId);
        Long count = jdbc.queryForObject(sql, params, Long.class);
        return count != null && count > 0;
    }

    @Override
    public void addMember(UUID tenantId, UUID teamId, UUID userId) {
        String sql = """
                INSERT INTO team_members (id, tenant_id, team_id, user_id, created_at)
                VALUES (:id, :tenantId, :teamId, :userId, :createdAt)
                ON CONFLICT (team_id, user_id) DO NOTHING
                """;
        var params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("tenantId", tenantId)
                .addValue("teamId", teamId)
                .addValue("userId", userId)
                .addValue("createdAt", java.sql.Timestamp.from(Instant.now()));
        jdbc.update(sql, params);
    }
}
