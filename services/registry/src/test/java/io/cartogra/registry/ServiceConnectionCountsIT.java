package io.cartogra.registry;

import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceConnectionCountsIT {

    @MockitoBean
    ServiceLifecycleEventProducer eventProducer;

    @MockitoBean
    TeamLifecycleEventProducer teamEventProducer;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @Test
    void countsByConnectionGroupsServicesByConnectionId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();
        UUID otherConnId = UUID.randomUUID();

        insertService(tenantId, UUID.randomUUID(), "svc-a", connId);
        insertService(tenantId, UUID.randomUUID(), "svc-b", connId);
        insertService(tenantId, UUID.randomUUID(), "svc-c", otherConnId);
        insertService(tenantId, UUID.randomUUID(), "svc-no-conn", null);

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/services/counts-by-connection"))
                        .header("X-Tenant-Id", tenantId.toString())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("X-Trace-Id")).isPresent();

        JsonNode counts = objectMapper.readTree(resp.body()).get("data").get("counts");
        assertThat(counts.get(connId.toString()).asLong()).isEqualTo(2L);
        assertThat(counts.get(otherConnId.toString()).asLong()).isEqualTo(1L);
        assertThat(counts.has("null")).isFalse();
    }

    @Test
    void countsByConnectionExcludesSoftDeletedServices() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connId = UUID.randomUUID();

        UUID activeId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        insertService(tenantId, activeId, "svc-active", connId);
        insertService(tenantId, deletedId, "svc-deleted", connId);
        jdbc.update("UPDATE services SET deleted_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", deletedId));

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/services/counts-by-connection"))
                        .header("X-Tenant-Id", tenantId.toString())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode counts = objectMapper.readTree(resp.body()).get("data").get("counts");
        assertThat(counts.get(connId.toString()).asLong()).isEqualTo(1L);
    }

    @Test
    void countsByConnectionIsolatesAcrossTenants() throws Exception {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();
        UUID connId = UUID.randomUUID();

        insertService(tenant1, UUID.randomUUID(), "svc-t1", connId);
        insertService(tenant2, UUID.randomUUID(), "svc-t2", connId);

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/services/counts-by-connection"))
                        .header("X-Tenant-Id", tenant1.toString())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode counts = objectMapper.readTree(resp.body()).get("data").get("counts");
        assertThat(counts.get(connId.toString()).asLong()).isEqualTo(1L);
    }

    @Test
    void countsByConnectionReturnsEmptyMapForTenantWithNoConnectedServices() throws Exception {
        UUID tenantId = UUID.randomUUID();

        HttpResponse<String> resp = HTTP.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/api/v1/services/counts-by-connection"))
                        .header("X-Tenant-Id", tenantId.toString())
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode counts = objectMapper.readTree(resp.body()).get("data").get("counts");
        assertThat(counts.isObject()).isTrue();
        assertThat(counts.size()).isZero();
    }

    private void insertService(UUID tenantId, UUID id, String name, UUID connectionId) {
        jdbc.update("""
                INSERT INTO services (id, tenant_id, name, health_status, connection_id, created_at, updated_at)
                VALUES (:id, :tenantId, :name, 'UNKNOWN', :connectionId, now(), now())
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("tenantId", tenantId)
                        .addValue("name", name)
                        .addValue("connectionId", connectionId));
    }
}
