package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.domain.DeliveryAttempt;
import io.cartogra.contract.repository.DeliveryAttemptRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcDeliveryAttemptRepository implements DeliveryAttemptRepository {

    private static final String COLUMNS = """
            id, tenant_id, rule_id, check_id, attempt, channel, destination_hint, wait_ms, status_code,
            error_code, outcome, created_at, finished_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDeliveryAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertPending(DeliveryAttempt log) {
        jdbc.update("""
                INSERT INTO notification_log (id, tenant_id, rule_id, check_id, attempt, channel, destination_hint,
                                               wait_ms, status_code, error_code, outcome, created_at, finished_at)
                VALUES (:id, :tenantId, :ruleId, :checkId, :attempt, :channel, :destinationHint, :waitMs, null,
                        null, :outcome, :createdAt, null)
                """, new MapSqlParameterSource()
                .addValue("id", log.id())
                .addValue("tenantId", log.tenantId())
                .addValue("ruleId", log.ruleId())
                .addValue("checkId", log.checkId())
                .addValue("attempt", log.attempt())
                .addValue("channel", log.channel())
                .addValue("destinationHint", log.destinationHint())
                .addValue("waitMs", log.waitMs())
                .addValue("outcome", log.outcome())
                .addValue("createdAt", Timestamp.from(log.createdAt())));
    }

    @Override
    public void finish(UUID tenantId, UUID id, @Nullable Integer statusCode, @Nullable String errorCode,
                       String outcome, Instant finishedAt) {
        jdbc.update("""
                UPDATE notification_log SET status_code = :statusCode, error_code = :errorCode, outcome = :outcome,
                                            finished_at = :finishedAt
                WHERE tenant_id = :tenantId AND id = :id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", id)
                .addValue("statusCode", statusCode)
                .addValue("errorCode", errorCode)
                .addValue("outcome", outcome)
                .addValue("finishedAt", Timestamp.from(finishedAt)));
    }

    @Override
    public List<DeliveryAttempt> listByRule(UUID tenantId, UUID ruleId, int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification_log "
                        + "WHERE tenant_id = :tenantId AND rule_id = :ruleId "
                        + "ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("ruleId", ruleId)
                        .addValue("limit", limit)
                        .addValue("offset", offset), mapper());
    }

    @Override
    public long countByRule(UUID tenantId, UUID ruleId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notification_log "
                        + "WHERE tenant_id = :tenantId AND rule_id = :ruleId",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("ruleId", ruleId), Long.class);
        return count == null ? 0 : count;
    }

    private static RowMapper<DeliveryAttempt> mapper() {
        return (rs, _) -> new DeliveryAttempt(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("rule_id"),
                (UUID) rs.getObject("check_id"),
                rs.getInt("attempt"),
                rs.getString("channel"),
                rs.getString("destination_hint"),
                rs.getInt("wait_ms"),
                (Integer) rs.getObject("status_code"),
                rs.getString("error_code"),
                rs.getString("outcome"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant());
    }
}
