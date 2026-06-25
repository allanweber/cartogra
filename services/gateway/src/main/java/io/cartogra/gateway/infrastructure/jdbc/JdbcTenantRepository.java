package io.cartogra.gateway.infrastructure.jdbc;

import io.cartogra.gateway.repository.TenantRepository;

import io.cartogra.gateway.domain.Tenant;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
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
            INSERT INTO tenants (id, tenant_id, name, slug, plan, created_at, updated_at)
            VALUES (:id, :tenantId, :name, :slug, :plan, :createdAt, :updatedAt)
            """;
        var params = new MapSqlParameterSource()
            .addValue("id", id)
            .addValue("tenantId", id)
            .addValue("name", tenant.name())
            .addValue("slug", slug)
            .addValue("plan", tenant.plan())
            .addValue("createdAt", Timestamp.from(now))
            .addValue("updatedAt", Timestamp.from(now));
        jdbc.update(sql, params);
        return new Tenant(id, id, tenant.name(), slug, tenant.plan(), now, now, null);
    }

    private static String slugify(String input) {
        return input.toLowerCase()
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
    }
}
