package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.domain.Invitation;
import io.cartogra.gateway.repository.InvitationRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcInvitationRepository implements InvitationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcInvitationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Invitation save(Invitation invitation) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String sql = """
            INSERT INTO invitations (id, tenant_id, email, role, invited_by, team_id,
                token, token_exp, status, created_at, updated_at)
            VALUES (:id, :tenantId, :email, :role, :invitedBy, :teamId,
                :token, :tokenExp, :status, :createdAt, :updatedAt)
            """;
        var params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", invitation.tenantId())
            .addValue("email", invitation.email())
            .addValue("role", invitation.role())
            .addValue("invitedBy", invitation.invitedBy())
            .addValue("teamId", invitation.teamId())
            .addValue("token", invitation.token())
            .addValue("tokenExp", Timestamp.from(invitation.tokenExp()))
            .addValue("status", invitation.status())
            .addValue("createdAt", Timestamp.from(now))
            .addValue("updatedAt", Timestamp.from(now));
        jdbc.update(sql, params);
        return new Invitation(id, invitation.tenantId(), invitation.email(), invitation.role(),
            invitation.invitedBy(), invitation.teamId(), invitation.token(), invitation.tokenExp(),
            invitation.status(), now, now);
    }

    @Override
    public Optional<Invitation> findByToken(String token) {
        String sql = "SELECT * FROM invitations WHERE token = :token";
        var params = new MapSqlParameterSource("token", token);
        return jdbc.query(sql, params, JdbcInvitationRepository::mapRow).stream().findFirst();
    }

    @Override
    public void markAccepted(UUID id) {
        String sql = "UPDATE invitations SET status = 'ACCEPTED', updated_at = now() WHERE id = :id";
        jdbc.update(sql, new MapSqlParameterSource("id", id));
    }

    private static Invitation mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Invitation(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("email"),
            rs.getString("role"),
            UUID.fromString(rs.getString("invited_by")),
            rs.getObject("team_id", UUID.class),
            rs.getString("token"),
            toInstant(rs.getTimestamp("token_exp")),
            rs.getString("status"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
