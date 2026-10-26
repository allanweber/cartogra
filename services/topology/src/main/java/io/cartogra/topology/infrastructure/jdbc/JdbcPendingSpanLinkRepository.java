package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.repository.MessagingEndpoint;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import io.cartogra.topology.repository.SpanRef;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class JdbcPendingSpanLinkRepository implements PendingSpanLinkRepository {

    private static final int DELETE_CHUNK_SIZE = 1000;
    private static final String LOCK_PREFIX = "pending_span_links:";
    private static final Comparator<MessagingEndpoint> ENDPOINT_LOCK_ORDER =
            Comparator.comparing(MessagingEndpoint::destination)
                    .thenComparing(MessagingEndpoint::kind)
                    .thenComparing(MessagingEndpoint::serviceName);

    private static final RowMapper<PendingSpanLink> MAPPER = (rs, _) -> new PendingSpanLink(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("trace_id"),
            rs.getString("span_id"),
            rs.getString("parent_span_id"),
            SpanKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
            rs.getString("service_name"),
            rs.getString("protocol") != null ? DependencyProtocol.fromDbValue(rs.getString("protocol")) : null,
            rs.getTimestamp("expires_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPendingSpanLinkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PendingSpanLink> takeClientPartner(UUID tenantId, String traceId, String parentSpanId) {
        return take(tenantId, traceId, "span_id", parentSpanId, "client");
    }

    @Override
    public Optional<PendingSpanLink> takeServerPartner(UUID tenantId, String traceId, String clientSpanId) {
        return take(tenantId, traceId, "parent_span_id", clientSpanId, "server");
    }

    private Optional<PendingSpanLink> take(UUID tenantId, String traceId, String spanColumn, String spanId,
                                           String kind) {
        lockTenant(tenantId);
        String sql = """
                DELETE FROM pending_span_links
                WHERE id = (SELECT id FROM pending_span_links
                            WHERE tenant_id = :tenantId AND trace_id = :traceId AND %s = :spanId
                              AND kind = :kind AND expires_at > now()
                            LIMIT 1)
                RETURNING *
                """.formatted(spanColumn);
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("traceId", traceId)
                .addValue("spanId", spanId)
                .addValue("kind", kind);
        return jdbc.query(sql, params, MAPPER).stream().findFirst();
    }

    @Override
    public void park(UUID tenantId, Collection<PendingSpanLink> links) {
        if (links.isEmpty()) {
            return;
        }
        lockTenant(tenantId);
        String sql = """
                INSERT INTO pending_span_links (tenant_id, trace_id, span_id, parent_span_id, kind,
                                                service_name, protocol, expires_at)
                VALUES (:tenantId, :traceId, :spanId, :parentSpanId, :kind, :serviceName, :protocol, :expiresAt)
                """;
        SqlParameterSource[] batch = links.stream()
                .map(link -> new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("traceId", link.traceId())
                        .addValue("spanId", link.spanId())
                        .addValue("parentSpanId", link.parentSpanId())
                        .addValue("kind", link.kind().name().toLowerCase(Locale.ROOT))
                        .addValue("serviceName", link.serviceName())
                        .addValue("protocol", link.protocol() != null ? link.protocol().toDbValue() : null)
                        .addValue("expiresAt", Timestamp.from(link.expiresAt())))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    /**
     * Transaction-scoped and taken once per transaction: the first call binds a marker to the transaction, so a batch
     * with many unmatched halves pays one round trip. One lock per tenant (not per trace) keeps lock-table usage
     * constant and rules out deadlocks between halves.
     */
    private void lockTenant(UUID tenantId) {
        String key = LOCK_PREFIX + tenantId;
        if (TransactionSynchronizationManager.hasResource(key)) {
            return;
        }
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", key), rs -> {});
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.bindResource(key, Boolean.TRUE);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(key);
                }
            });
        }
    }

    @Override
    public int deleteServerPartners(UUID tenantId, Collection<SpanRef> clientSpans) {
        String sql = """
                DELETE FROM pending_span_links
                WHERE tenant_id = :tenantId AND kind = 'server'
                  AND (trace_id, parent_span_id) IN (:spans)
                """;
        List<Object[]> spans = clientSpans.stream()
                .map(ref -> new Object[] {ref.traceId(), ref.spanId()})
                .toList();
        int deleted = 0;
        for (int from = 0; from < spans.size(); from += DELETE_CHUNK_SIZE) {
            var params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("spans", spans.subList(from, Math.min(from + DELETE_CHUNK_SIZE, spans.size())));
            deleted += jdbc.update(sql, params);
        }
        return deleted;
    }

    @Override
    public Set<MessagingEndpoint> joinMessagingEndpoints(UUID tenantId, Collection<MessagingEndpoint> batch,
                                                         Instant expiresAt) {
        if (batch.isEmpty()) {
            return Set.of();
        }
        String upsert = """
                INSERT INTO pending_span_links (tenant_id, kind, destination, service_name, protocol, expires_at)
                VALUES (:tenantId, :kind, :destination, :serviceName, 'kafka', :expiresAt)
                ON CONFLICT (tenant_id, destination, kind, lower(service_name)) WHERE destination IS NOT NULL
                DO UPDATE SET expires_at = EXCLUDED.expires_at
                    WHERE pending_span_links.expires_at < EXCLUDED.expires_at - interval '1 minute'
                """;
        SqlParameterSource[] rows = batch.stream()
                .distinct()
                .sorted(ENDPOINT_LOCK_ORDER)
                .map(endpoint -> new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("kind", endpoint.kind().name().toLowerCase(Locale.ROOT))
                        .addValue("destination", endpoint.destination())
                        .addValue("serviceName", endpoint.serviceName())
                        .addValue("expiresAt", Timestamp.from(expiresAt)))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate(upsert, rows);

        String select = """
                SELECT destination, kind, service_name FROM pending_span_links
                WHERE tenant_id = :tenantId AND destination IN (:destinations)
                  AND kind IN ('producer', 'consumer') AND expires_at > now()
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("destinations", batch.stream().map(MessagingEndpoint::destination).distinct().toList());
        Set<MessagingEndpoint> live = new LinkedHashSet<>(batch);
        live.addAll(jdbc.query(select, params, (rs, _) -> new MessagingEndpoint(
                rs.getString("destination"),
                SpanKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
                rs.getString("service_name"))));
        return live;
    }

    @Override
    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM pending_span_links WHERE expires_at <= :now",
                new MapSqlParameterSource("now", Timestamp.from(now)));
    }
}
