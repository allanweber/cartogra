package io.cartogra.topology.infrastructure.jdbc;

import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.repository.PendingSpanLink;
import io.cartogra.topology.repository.PendingSpanLinkRepository;
import io.cartogra.topology.repository.SpanRef;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcPendingSpanLinkRepository implements PendingSpanLinkRepository {

    private static final int DELETE_CHUNK_SIZE = 1000;

    private static final RowMapper<PendingSpanLink> MAPPER = (rs, _) -> new PendingSpanLink(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("tenant_id")),
            rs.getString("trace_id"),
            rs.getString("span_id"),
            rs.getString("parent_span_id"),
            SpanKind.valueOf(rs.getString("kind").toUpperCase(Locale.ROOT)),
            rs.getString("service_name"),
            rs.getString("protocol"),
            rs.getTimestamp("expires_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPendingSpanLinkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PendingSpanLink> findClientPartner(UUID tenantId, String traceId, String parentSpanId) {
        String sql = """
                SELECT * FROM pending_span_links
                WHERE tenant_id = :tenantId AND trace_id = :traceId AND span_id = :spanId
                  AND kind = 'client' AND expires_at > now()
                LIMIT 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("traceId", traceId)
                .addValue("spanId", parentSpanId);
        return jdbc.query(sql, params, MAPPER).stream().findFirst();
    }

    @Override
    public Optional<PendingSpanLink> findServerPartner(UUID tenantId, String traceId, String clientSpanId) {
        String sql = """
                SELECT * FROM pending_span_links
                WHERE tenant_id = :tenantId AND trace_id = :traceId AND parent_span_id = :spanId
                  AND kind = 'server' AND expires_at > now()
                LIMIT 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("traceId", traceId)
                .addValue("spanId", clientSpanId);
        return jdbc.query(sql, params, MAPPER).stream().findFirst();
    }

    @Override
    public void lockTenant(UUID tenantId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "pending_span_links:" + tenantId), rs -> {});
    }

    @Override
    public void insert(PendingSpanLink link) {
        String sql = """
                INSERT INTO pending_span_links (tenant_id, trace_id, span_id, parent_span_id, kind,
                                                service_name, protocol, expires_at)
                VALUES (:tenantId, :traceId, :spanId, :parentSpanId, :kind, :serviceName, :protocol, :expiresAt)
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", link.tenantId())
                .addValue("traceId", link.traceId())
                .addValue("spanId", link.spanId())
                .addValue("parentSpanId", link.parentSpanId())
                .addValue("kind", link.kind().name().toLowerCase(Locale.ROOT))
                .addValue("serviceName", link.serviceName())
                .addValue("protocol", link.protocol())
                .addValue("expiresAt", Timestamp.from(link.expiresAt()));
        jdbc.update(sql, params);
    }

    @Override
    public void delete(UUID id) {
        jdbc.update("DELETE FROM pending_span_links WHERE id = :id", new MapSqlParameterSource("id", id));
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
    public int deleteExpired(Instant now) {
        return jdbc.update("DELETE FROM pending_span_links WHERE expires_at <= :now",
                new MapSqlParameterSource("now", Timestamp.from(now)));
    }
}
