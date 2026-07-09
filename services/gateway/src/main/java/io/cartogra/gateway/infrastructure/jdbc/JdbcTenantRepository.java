package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.repository.TenantRepository;

import io.cartogra.gateway.domain.Tenant;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTenantRepository implements TenantRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTenantRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Tenant save(Tenant tenant) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        String slug = slugify(tenant.name()) + "-" + id.toString().substring(0, 8);
        String sql = """
            INSERT INTO tenants (id, tenant_id, name, slug, plan_id, created_at, updated_at)
            VALUES (:id, :tenantId, :name, :slug, (SELECT id FROM billing_plans WHERE slug = :planSlug), :createdAt, :updatedAt)
            """;
        var params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", id)
            .addValue("name", tenant.name())
            .addValue("slug", slug)
            .addValue("planSlug", tenant.plan())
            .addValue("createdAt", Timestamp.from(now))
            .addValue("updatedAt", Timestamp.from(now));
        jdbc.update(sql, params);
        return new Tenant(id, id, tenant.name(), slug, tenant.plan(), now, now, null);
    }

    @Override
    public Optional<Tenant> findByTenantId(UUID tenantId) {
        String sql = """
            SELECT t.id, t.tenant_id, t.name, t.slug, bp.slug AS plan_slug, t.created_at, t.updated_at
            FROM tenants t
            JOIN billing_plans bp ON bp.id = t.plan_id
            WHERE t.tenant_id = :tenantId AND t.deleted_at IS NULL
            """;
        var params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        return jdbc.query(sql, params, (rs, _) -> new Tenant(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("name"),
            rs.getString("slug"),
            rs.getString("plan_slug"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            null
        )).stream().findFirst();
    }

    private static String slugify(String input) {
        return input.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }
}
