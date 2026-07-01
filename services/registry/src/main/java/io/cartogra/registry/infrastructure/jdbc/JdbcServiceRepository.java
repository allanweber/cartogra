package io.cartogra.registry.infrastructure.jdbc;

import io.cartogra.registry.repository.ServiceDiscoveryCommand;
import io.cartogra.registry.repository.ServiceFilter;
import io.cartogra.registry.repository.ServiceRepository;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.ServiceHealthStatus;
import io.cartogra.registry.domain.ServiceTier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                    created_at, updated_at, deleted_at,
                    external_id, connection_id, source, repository_ref,
                    k8s_cluster, k8s_namespace, k8s_deployment, health_endpoint,
                    last_commit_at, last_commit_sha, health_checked_at,
                    tier, tags, sla_target, documentation_url, runbook_url
                ) VALUES (
                    :id, :tenantId, :name, :description, :teamId, :repositoryUrl,
                    :techStack, CAST(:metadata AS JSONB), :healthStatus, :lastDeployedAt,
                    :createdAt, :updatedAt, :deletedAt,
                    :externalId, :connectionId, :source, :repositoryRef,
                    :k8sCluster, :k8sNamespace, :k8sDeployment, :healthEndpoint,
                    :lastCommitAt, :lastCommitSha, :healthCheckedAt,
                    :tier, :tags, :slaTarget, :documentationUrl, :runbookUrl
                )
                ON CONFLICT (id) DO UPDATE SET
                    name               = EXCLUDED.name,
                    description        = EXCLUDED.description,
                    team_id            = EXCLUDED.team_id,
                    repository_url     = EXCLUDED.repository_url,
                    tech_stack         = EXCLUDED.tech_stack,
                    metadata           = EXCLUDED.metadata,
                    health_status      = EXCLUDED.health_status,
                    last_deployed_at   = EXCLUDED.last_deployed_at,
                    updated_at         = EXCLUDED.updated_at,
                    deleted_at         = EXCLUDED.deleted_at,
                    external_id        = EXCLUDED.external_id,
                    connection_id      = EXCLUDED.connection_id,
                    source             = EXCLUDED.source,
                    repository_ref     = EXCLUDED.repository_ref,
                    k8s_cluster        = EXCLUDED.k8s_cluster,
                    k8s_namespace      = EXCLUDED.k8s_namespace,
                    k8s_deployment     = EXCLUDED.k8s_deployment,
                    health_endpoint    = EXCLUDED.health_endpoint,
                    last_commit_at     = EXCLUDED.last_commit_at,
                    last_commit_sha    = EXCLUDED.last_commit_sha,
                    health_checked_at  = EXCLUDED.health_checked_at,
                    tier               = EXCLUDED.tier,
                    tags               = EXCLUDED.tags,
                    sla_target         = EXCLUDED.sla_target,
                    documentation_url  = EXCLUDED.documentation_url,
                    runbook_url        = EXCLUDED.runbook_url
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

    @Override
    public Optional<Service> findByExternalId(UUID tenantId, String externalId) {
        String sql = """
                SELECT * FROM services
                WHERE tenant_id = :tenantId AND external_id = :externalId AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("externalId", externalId);
        return jdbc.query(sql, params, SERVICE_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<Service> upsertDiscovered(ServiceDiscoveryCommand command) {
        Instant now = Instant.now();

        // First try by externalId (the discovery key). If that misses, fall back to name so a
        // service created manually before discovery was enabled gets claimed rather than duplicated.
        Optional<Service> existing = findByExternalId(command.tenantId(), command.externalId());
        if (existing.isEmpty()) {
            existing = findByName(command.tenantId(), command.name());
        }
        if (existing.isPresent()) {
            Service current = existing.get();
            String description   = command.description()   != null ? command.description()   : current.description();
            String repositoryUrl = command.repositoryUrl() != null ? command.repositoryUrl() : current.repositoryUrl();
            List<String> techStack = !command.techStack().isEmpty() ? command.techStack()    : current.techStack();
            ServiceHealthStatus healthStatus = ServiceHealthStatus.fromString(command.healthStatus());
            String repositoryRef = command.repositoryRef() != null ? command.repositoryRef() : current.repositoryRef();
            String k8sCluster    = command.k8sCluster()    != null ? command.k8sCluster()    : current.k8sCluster();
            String k8sNamespace  = command.k8sNamespace()  != null ? command.k8sNamespace()  : current.k8sNamespace();
            String k8sDeployment = command.k8sDeployment() != null ? command.k8sDeployment() : current.k8sDeployment();
            String healthEndpoint = command.healthEndpoint() != null ? command.healthEndpoint() : current.healthEndpoint();
            Instant lastCommitAt  = command.lastCommitAt()  != null ? command.lastCommitAt()  : current.lastCommitAt();
            String lastCommitSha  = command.lastCommitSha() != null ? command.lastCommitSha() : current.lastCommitSha();

            boolean changed = !Objects.equals(current.description(), description)
                    || !Objects.equals(current.repositoryUrl(), repositoryUrl)
                    || !Objects.equals(current.techStack(), techStack)
                    || current.healthStatus() != healthStatus
                    || !Objects.equals(current.connectionId(), command.connectionId())
                    || !Objects.equals(current.source(), command.source())
                    || !Objects.equals(current.repositoryRef(), repositoryRef)
                    || !Objects.equals(current.k8sCluster(), k8sCluster)
                    || !Objects.equals(current.k8sNamespace(), k8sNamespace)
                    || !Objects.equals(current.k8sDeployment(), k8sDeployment)
                    || !Objects.equals(current.healthEndpoint(), healthEndpoint)
                    || !Objects.equals(current.lastCommitAt(), lastCommitAt)
                    || !Objects.equals(current.lastCommitSha(), lastCommitSha);

            if (!changed) {
                return Optional.empty();
            }

            var updated = new Service(
                    current.id(), current.tenantId(), current.name(),
                    description, current.teamId(), repositoryUrl, techStack, current.metadata(),
                    healthStatus, current.lastDeployedAt(), current.createdAt(), now, null,
                    current.externalId(), command.connectionId(), command.source(),
                    repositoryRef, k8sCluster, k8sNamespace, k8sDeployment,
                    healthEndpoint, lastCommitAt, lastCommitSha, current.healthCheckedAt(),
                    current.tier(), current.tags(), current.slaTarget(),
                    current.documentationUrl(), current.runbookUrl()
            );
            return Optional.of(save(updated));
        }

        var newService = new Service(
                UUID.randomUUID(),
                command.tenantId(),
                command.name(),
                command.description(),
                null,
                command.repositoryUrl(),
                command.techStack().isEmpty() ? null : command.techStack(),
                null,
                ServiceHealthStatus.fromString(command.healthStatus()),
                null,
                now,
                now,
                null,
                command.externalId(),
                command.connectionId(),
                command.source(),
                command.repositoryRef(),
                command.k8sCluster(),
                command.k8sNamespace(),
                command.k8sDeployment(),
                command.healthEndpoint(),
                command.lastCommitAt(),
                command.lastCommitSha(),
                null,
                null, null, null, null, null
        );
        return Optional.of(save(newService));
    }

    @Override
    public List<Service> findAllWithHealthEndpoint() {
        String sql = """
                SELECT * FROM services
                WHERE health_endpoint IS NOT NULL
                  AND source IS DISTINCT FROM 'kubernetes'
                  AND deleted_at IS NULL
                """;
        return jdbc.query(sql, Map.of(), SERVICE_MAPPER);
    }

    @Override
    public void updateHealth(UUID tenantId, UUID id, ServiceHealthStatus status, Instant checkedAt) {
        String sql = """
                UPDATE services
                SET health_status = :healthStatus,
                    health_checked_at = :checkedAt,
                    updated_at = :checkedAt
                WHERE tenant_id = :tenantId AND id = :id AND deleted_at IS NULL
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("healthStatus", status.toDbValue())
                .addValue("checkedAt", java.sql.Timestamp.from(checkedAt))
                .addValue("tenantId", tenantId)
                .addValue("id", id));
    }

    private Optional<Service> findByName(UUID tenantId, String name) {
        String sql = """
                SELECT * FROM services
                WHERE tenant_id = :tenantId AND lower(name) = lower(:name) AND deleted_at IS NULL
                LIMIT 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("name", name);
        return jdbc.query(sql, params, SERVICE_MAPPER).stream().findFirst();
    }

    @Override
    public List<String> findDistinctTechStacks(UUID tenantId) {
        String sql = """
                SELECT DISTINCT unnest(tech_stack) AS tech
                FROM services
                WHERE tenant_id = :tenantId AND tech_stack IS NOT NULL AND deleted_at IS NULL
                ORDER BY tech ASC
                """;
        return jdbc.queryForList(sql, new MapSqlParameterSource("tenantId", tenantId), String.class);
    }

    private void applyFilter(StringBuilder sql, MapSqlParameterSource params, ServiceFilter filter) {
        if (filter.teamId() != null) {
            sql.append(" AND team_id = :teamId");
            params.addValue("teamId", filter.teamId());
        }
        if (filter.healthStatus() != null) {
            ServiceHealthStatus requested = ServiceHealthStatus.fromString(filter.healthStatus());
            if (requested == ServiceHealthStatus.DEGRADED) {
                sql.append(" AND health_status IN (:healthStatuses)");
                params.addValue("healthStatuses", List.of("degraded", "unknown", "probe_auth_failed"));
            } else {
                sql.append(" AND health_status = :healthStatus");
                params.addValue("healthStatus", requested.toDbValue());
            }
        }
        if (filter.techStackQuery() != null && !filter.techStackQuery().isEmpty()) {
            sql.append(" AND tech_stack && ARRAY[:techStackQuery]::TEXT[]");
            params.addValue("techStackQuery", filter.techStackQuery().toArray(String[]::new));
        }
        if (filter.source() != null) {
            sql.append(" AND source = :source");
            params.addValue("source", filter.source());
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
                .addValue("techStack", s.techStack() != null ? s.techStack().toArray(String[]::new) : null)
                .addValue("metadata", s.metadata())
                .addValue("healthStatus", s.healthStatus().toDbValue())
                .addValue("lastDeployedAt", s.lastDeployedAt() != null ? java.sql.Timestamp.from(s.lastDeployedAt()) : null)
                .addValue("createdAt", java.sql.Timestamp.from(s.createdAt()))
                .addValue("updatedAt", java.sql.Timestamp.from(s.updatedAt()))
                .addValue("deletedAt", s.deletedAt() != null ? java.sql.Timestamp.from(s.deletedAt()) : null)
                .addValue("externalId", s.externalId())
                .addValue("connectionId", s.connectionId())
                .addValue("source", s.source())
                .addValue("repositoryRef", s.repositoryRef())
                .addValue("k8sCluster", s.k8sCluster())
                .addValue("k8sNamespace", s.k8sNamespace())
                .addValue("k8sDeployment", s.k8sDeployment())
                .addValue("healthEndpoint", s.healthEndpoint())
                .addValue("lastCommitAt", s.lastCommitAt() != null ? java.sql.Timestamp.from(s.lastCommitAt()) : null)
                .addValue("lastCommitSha", s.lastCommitSha())
                .addValue("healthCheckedAt", s.healthCheckedAt() != null ? java.sql.Timestamp.from(s.healthCheckedAt()) : null)
                .addValue("tier", s.tier() != null ? s.tier().toDbValue() : null)
                .addValue("tags", s.tags() != null ? s.tags().toArray(String[]::new) : null)
                .addValue("slaTarget", s.slaTarget())
                .addValue("documentationUrl", s.documentationUrl())
                .addValue("runbookUrl", s.runbookUrl());
    }

    private static final RowMapper<Service> SERVICE_MAPPER = (rs, _) -> mapService(rs);

    private static Service mapService(ResultSet rs) throws SQLException {
        java.sql.Array techStackArr = rs.getArray("tech_stack");
        List<String> techStack = techStackArr != null ? List.of((String[]) techStackArr.getArray()) : null;
        java.sql.Array tagsArr = rs.getArray("tags");
        List<String> tags = tagsArr != null ? List.of((String[]) tagsArr.getArray()) : null;
        String tierStr = rs.getString("tier");
        ServiceTier tier = tierStr != null ? ServiceTier.valueOf(tierStr.toUpperCase()) : null;
        BigDecimal slaTarget = rs.getBigDecimal("sla_target");
        return new Service(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("tenant_id")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("team_id") != null ? UUID.fromString(rs.getString("team_id")) : null,
                rs.getString("repository_url"),
                techStack,
                rs.getString("metadata"),
                ServiceHealthStatus.fromString(rs.getString("health_status")),
                rs.getTimestamp("last_deployed_at") != null ? rs.getTimestamp("last_deployed_at").toInstant() : null,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("deleted_at") != null ? rs.getTimestamp("deleted_at").toInstant() : null,
                rs.getString("external_id"),
                rs.getString("connection_id") != null ? UUID.fromString(rs.getString("connection_id")) : null,
                rs.getString("source"),
                rs.getString("repository_ref"),
                rs.getString("k8s_cluster"),
                rs.getString("k8s_namespace"),
                rs.getString("k8s_deployment"),
                rs.getString("health_endpoint"),
                rs.getTimestamp("last_commit_at") != null ? rs.getTimestamp("last_commit_at").toInstant() : null,
                rs.getString("last_commit_sha"),
                rs.getTimestamp("health_checked_at") != null ? rs.getTimestamp("health_checked_at").toInstant() : null,
                tier,
                tags,
                slaTarget,
                rs.getString("documentation_url"),
                rs.getString("runbook_url")
        );
    }
}
