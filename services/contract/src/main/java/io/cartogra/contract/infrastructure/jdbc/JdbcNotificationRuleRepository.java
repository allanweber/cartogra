package io.cartogra.contract.infrastructure.jdbc;

import io.cartogra.contract.domain.NotificationRule;
import io.cartogra.contract.repository.NotificationRuleRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcNotificationRuleRepository implements NotificationRuleRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, tenant_id, service_id, enabled, slack_webhook_url, teams_webhook_url, email_addresses,
            created_at, updated_at, deleted_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcNotificationRuleRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void insert(NotificationRule rule) {
        jdbc.update("""
                INSERT INTO notification_rules (id, tenant_id, service_id, enabled, slack_webhook_url,
                                                 teams_webhook_url, email_addresses, created_at, updated_at, deleted_at)
                VALUES (:id, :tenantId, :serviceId, :enabled, :slackWebhookUrl, :teamsWebhookUrl,
                        CAST(:emailAddresses AS jsonb), :createdAt, :updatedAt, :deletedAt)
                """, new MapSqlParameterSource()
                .addValue("id", rule.id())
                .addValue("tenantId", rule.tenantId())
                .addValue("serviceId", rule.serviceId())
                .addValue("enabled", rule.enabled())
                .addValue("slackWebhookUrl", rule.slackWebhookUrl())
                .addValue("teamsWebhookUrl", rule.teamsWebhookUrl())
                .addValue("emailAddresses", objectMapper.writeValueAsString(rule.emailAddresses()))
                .addValue("createdAt", Timestamp.from(rule.createdAt()))
                .addValue("updatedAt", Timestamp.from(rule.updatedAt()))
                .addValue("deletedAt", rule.deletedAt() == null ? null : Timestamp.from(rule.deletedAt())));
    }

    @Override
    public Optional<NotificationRule> findLive(UUID tenantId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification_rules "
                        + "WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("id", id), mapper())
                .stream().findFirst();
    }

    @Override
    public List<NotificationRule> listLive(UUID tenantId, int limit, int offset) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification_rules "
                        + "WHERE tenant_id = :tenantId AND deleted_at IS NULL "
                        + "ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("limit", limit)
                        .addValue("offset", offset), mapper());
    }

    @Override
    public long countLive(UUID tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notification_rules "
                        + "WHERE tenant_id = :tenantId AND deleted_at IS NULL",
                new MapSqlParameterSource().addValue("tenantId", tenantId), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<NotificationRule> findMatching(UUID tenantId, UUID serviceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notification_rules "
                        + "WHERE tenant_id = :tenantId AND deleted_at IS NULL "
                        + "AND (service_id IS NULL OR service_id = :serviceId) ORDER BY service_id NULLS FIRST, id",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("serviceId", serviceId), mapper());
    }

    @Override
    public Optional<NotificationRule> update(UUID tenantId, UUID id, @Nullable String slackWebhookUrl,
                                             @Nullable String teamsWebhookUrl, List<String> emailAddresses,
                                             Instant at) {
        return jdbc.query("UPDATE notification_rules SET slack_webhook_url = :slackWebhookUrl, "
                        + "teams_webhook_url = :teamsWebhookUrl, email_addresses = CAST(:emailAddresses AS jsonb), "
                        + "updated_at = :at WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL "
                        + "RETURNING " + COLUMNS,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("id", id)
                        .addValue("slackWebhookUrl", slackWebhookUrl)
                        .addValue("teamsWebhookUrl", teamsWebhookUrl)
                        .addValue("emailAddresses", objectMapper.writeValueAsString(emailAddresses))
                        .addValue("at", Timestamp.from(at)), mapper()).stream().findFirst();
    }

    @Override
    public Optional<NotificationRule> setEnabled(UUID tenantId, UUID id, boolean enabled, Instant at) {
        return jdbc.query("UPDATE notification_rules SET enabled = :enabled, updated_at = :at "
                        + "WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL RETURNING " + COLUMNS,
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("id", id)
                        .addValue("enabled", enabled)
                        .addValue("at", Timestamp.from(at)), mapper()).stream().findFirst();
    }

    @Override
    public boolean softDelete(UUID tenantId, UUID id, Instant at) {
        return jdbc.update("UPDATE notification_rules SET deleted_at = :at, updated_at = :at "
                        + "WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("id", id)
                        .addValue("at", Timestamp.from(at))) == 1;
    }

    private RowMapper<NotificationRule> mapper() {
        return (rs, _) -> new NotificationRule(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("tenant_id"),
                (UUID) rs.getObject("service_id"),
                rs.getBoolean("enabled"),
                rs.getString("slack_webhook_url"),
                rs.getString("teams_webhook_url"),
                readEmails(rs),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant());
    }

    private List<String> readEmails(ResultSet rs) throws SQLException {
        String json = rs.getString("email_addresses");
        return json == null ? List.of() : objectMapper.readValue(json, STRING_LIST);
    }
}
