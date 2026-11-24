package io.cartogra.registry;

import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.web.security.InternalTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Create, list, and revoke of tenant API keys (plan 034: S-1 to S-7). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiKeyCrudIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String PATH = "/api/v1/registry/api-keys";

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", () -> PostgresTestSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresTestSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresTestSupport.OWNER_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Test
    void createReturnsTheKeyOnceAndStoresOnlyTheHash() throws Exception {
        UUID tenant = seedTenant("enterprise");
        UUID admin = createUser(tenant);

        HttpResponse<String> resp = send("POST", tenant, admin, "ADMIN", PATH,
                "{\"name\":\"ci\",\"scopes\":[\"ci:check\"]}");

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        String key = data.get("key").asString();
        assertThat(key).startsWith("cgk_");
        assertThat(data.get("status").asString()).isEqualTo("active");
        assertThat(data.get("scopes").get(0).asString()).isEqualTo("ci:check");
        UUID id = UUID.fromString(data.get("id").asString());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT key_hash, key_prefix, created_by FROM api_keys WHERE id = :id", Map.of("id", id));
        assertThat(row.get("key_hash").toString().trim()).isEqualTo(sha256(key));
        assertThat(row.get("key_hash").toString()).isNotEqualTo(key);
        assertThat(key).startsWith(row.get("key_prefix").toString());
        assertThat(row.get("key_prefix").toString()).isNotEqualTo(key);
        assertThat(row.get("created_by")).isEqualTo(admin);

        List<Map<String, Object>> events = auditEvents(tenant, id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("action")).isEqualTo("created");
        assertThat(events.get(0).get("actor_id")).isEqualTo(admin);
        String payload = events.get(0).get("payload").toString();
        assertThat(payload).doesNotContain(key).doesNotContain(sha256(key));
        assertThat(payload).contains("ci");

        // the list never shows the key again
        HttpResponse<String> list = send("GET", tenant, admin, "ADMIN", PATH, null);
        assertThat(list.body()).doesNotContain(key);
    }

    @Test
    void createAcceptsAFutureExpiry() throws Exception {
        UUID tenant = seedTenant("enterprise");
        UUID admin = createUser(tenant);
        String expiry = Instant.now().plus(30, ChronoUnit.DAYS).toString();

        HttpResponse<String> resp = send("POST", tenant, admin, "ADMIN", PATH,
                "{\"name\":\"ci\",\"scopes\":[\"ci:check\"],\"expiresAt\":\"" + expiry + "\"}");

        assertThat(resp.statusCode()).isEqualTo(201);
        assertThat(objectMapper.readTree(resp.body()).get("data").get("expiresAt").isNull()).isFalse();
    }

    @Test
    void createRejectsInvalidInputWith400() throws Exception {
        UUID tenant = seedTenant("enterprise");
        UUID admin = createUser(tenant);
        String past = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        List<String> bodies = List.of(
                "{\"name\":\"ci\",\"scopes\":[]}",
                "{\"name\":\"ci\",\"scopes\":[\"admin\"]}",
                "{\"name\":\"  \",\"scopes\":[\"ci:check\"]}",
                "{\"name\":\"" + "a".repeat(101) + "\",\"scopes\":[\"ci:check\"]}",
                "{\"name\":\"ci\",\"scopes\":[\"ci:check\"],\"expiresAt\":\"" + past + "\"}");

        for (String body : bodies) {
            HttpResponse<String> resp = send("POST", tenant, admin, "ADMIN", PATH, body);
            assertThat(resp.statusCode()).as(body).isEqualTo(400);
        }
        assertThat(count("SELECT COUNT(*) FROM api_keys WHERE tenant_id = :t", tenant)).isZero();
    }

    @Test
    void planLimitCountsActiveOnly() throws Exception {
        UUID tenant = seedTenant("free"); // max 2 keys
        UUID admin = createUser(tenant);
        insertKey(tenant, admin, "active", null, null);
        insertKey(tenant, admin, "expired", Instant.now().minus(1, ChronoUnit.DAYS), null);
        insertKey(tenant, admin, "revoked", null, Instant.now().minus(1, ChronoUnit.HOURS));

        HttpResponse<String> second = send("POST", tenant, admin, "ADMIN", PATH,
                "{\"name\":\"second\",\"scopes\":[\"ci:check\"]}");
        HttpResponse<String> third = send("POST", tenant, admin, "ADMIN", PATH,
                "{\"name\":\"third\",\"scopes\":[\"ci:check\"]}");

        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(third.statusCode()).isEqualTo(402);
        assertThat(objectMapper.readTree(third.body()).get("error").get("code").asString())
                .isEqualTo("PLAN_LIMIT_EXCEEDED");
    }

    @Test
    void listShowsStatusesWithoutSecrets() throws Exception {
        UUID tenant = seedTenant("enterprise");
        UUID other = seedTenant("enterprise");
        UUID admin = createUser(tenant);
        UUID viewer = createUser(tenant);
        insertKey(tenant, admin, "key-active", null, null);
        insertKey(tenant, admin, "key-expired", Instant.now().minus(1, ChronoUnit.DAYS), null);
        insertKey(tenant, admin, "key-revoked", null, Instant.now().minus(1, ChronoUnit.HOURS));
        insertKey(other, createUser(other), "key-other-tenant", null, null);

        HttpResponse<String> resp = send("GET", tenant, viewer, "VIEWER", PATH + "?limit=200", null);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode items = objectMapper.readTree(resp.body()).get("data").get("items");
        assertThat(items.size()).isEqualTo(3);
        Map<String, String> statusByName = new java.util.HashMap<>();
        for (JsonNode item : items) {
            statusByName.put(item.get("name").asString(), item.get("status").asString());
            assertThat(item.has("key")).isFalse();
            assertThat(item.has("keyHash")).isFalse();
            assertThat(item.has("key_hash")).isFalse();
            assertThat(item.has("prefix")).isTrue();
        }
        assertThat(statusByName).containsOnly(
                Map.entry("key-active", "active"),
                Map.entry("key-expired", "expired"),
                Map.entry("key-revoked", "revoked"));
        assertThat(resp.body()).doesNotContain("key-other-tenant");
        assertThat(resp.body()).doesNotContain("hash");
    }

    @Test
    void revokeIsIdempotentAndAuditedOnce() throws Exception {
        UUID tenant = seedTenant("enterprise");
        UUID admin = createUser(tenant);
        UUID id = insertKey(tenant, admin, "to-revoke", null, null);

        HttpResponse<String> first = send("DELETE", tenant, admin, "ADMIN", PATH + "/" + id, null);
        HttpResponse<String> second = send("DELETE", tenant, admin, "ADMIN", PATH + "/" + id, null);

        assertThat(first.statusCode()).isEqualTo(204);
        assertThat(second.statusCode()).isEqualTo(204);
        assertThat(count("SELECT COUNT(*) FROM api_keys WHERE id = :t AND revoked_at IS NOT NULL", id)).isEqualTo(1);
        List<Map<String, Object>> events = auditEvents(tenant, id);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("action")).isEqualTo("revoked");

        HttpResponse<String> list = send("GET", tenant, admin, "ADMIN", PATH, null);
        assertThat(objectMapper.readTree(list.body()).get("data").get("items").get(0).get("status").asString())
                .isEqualTo("revoked");
    }

    @Test
    void revokeOfAnUnknownOrForeignKeyReturns404() throws Exception {
        UUID tenant = seedTenant("enterprise");
        UUID other = seedTenant("enterprise");
        UUID admin = createUser(tenant);
        UUID foreign = insertKey(other, createUser(other), "foreign", null, null);

        for (UUID id : List.of(UUID.randomUUID(), foreign)) {
            HttpResponse<String> resp = send("DELETE", tenant, admin, "ADMIN", PATH + "/" + id, null);
            assertThat(resp.statusCode()).isEqualTo(404);
            assertThat(objectMapper.readTree(resp.body()).get("error").get("code").asString())
                    .isEqualTo("API_KEY_NOT_FOUND");
        }
        assertThat(count("SELECT COUNT(*) FROM api_keys WHERE id = :t AND revoked_at IS NULL", foreign)).isEqualTo(1);
    }

    private HttpResponse<String> send(String method, UUID tenant, UUID user, String role, String path, String body)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenant.toString())
                .header(InternalTokens.HEADER, InternalTokens.token("registry", tenant, user, role))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID seedTenant(String planSlug) {
        UUID tenantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tenants (tenant_id, name, slug, plan_id)
                SELECT :tenantId, 'Test Tenant', 'test-tenant-' || :tenantId,
                       (SELECT id FROM billing_plans WHERE slug = :plan)
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("plan", planSlug));
        return tenantId;
    }

    private UUID createUser(UUID tenantId) {
        return jdbc.queryForObject("""
                INSERT INTO users (tenant_id, email, auth_provider, roles)
                VALUES (:tenantId, :email, 'local', '{ADMIN}') RETURNING id
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("email", UUID.randomUUID() + "@test.invalid"), UUID.class);
    }

    private UUID insertKey(UUID tenant, UUID creator, String name, Instant expiresAt, Instant revokedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO api_keys (id, tenant_id, name, key_hash, key_prefix, scopes, expires_at, revoked_at, created_by)
                VALUES (:id, :t, :name, :hash, 'cgk_abcdefgh', ARRAY['ci:check'], :exp, :rev, :by)
                """, new MapSqlParameterSource().addValue("id", id).addValue("t", tenant).addValue("name", name)
                .addValue("hash", sha256(id.toString()))
                .addValue("exp", expiresAt == null ? null : java.sql.Timestamp.from(expiresAt))
                .addValue("rev", revokedAt == null ? null : java.sql.Timestamp.from(revokedAt))
                .addValue("by", creator));
        return id;
    }

    private List<Map<String, Object>> auditEvents(UUID tenant, UUID entityId) {
        return jdbc.queryForList("""
                SELECT action, actor_id, payload::text AS payload FROM audit_events
                WHERE tenant_id = :t AND entity_type = 'api_key' AND entity_id = :e
                """, new MapSqlParameterSource().addValue("t", tenant).addValue("e", entityId));
    }

    private int count(String sql, UUID param) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource("t", param), Integer.class);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
