package io.cartogra.registry;

import io.cartogra.registry.infrastructure.kafka.ServiceLifecycleEventProducer;
import io.cartogra.registry.infrastructure.kafka.TeamLifecycleEventProducer;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Responses to a GUEST carry no user identifiers; the frontend never has anything to hide (ADR-0019 §8). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GuestRedactionIT {

    @MockitoBean
    ServiceLifecycleEventProducer eventProducer;

    @MockitoBean
    TeamLifecycleEventProducer teamEventProducer;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private UUID tenantId;
    private UUID adminId;

    @BeforeEach
    void seed() {
        tenantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tenants (tenant_id, name, slug, plan_id)
                VALUES (:tenantId, 'Guest Tenant', 'guest-' || :tenantId, (SELECT id FROM billing_plans WHERE slug = 'free'))
                """, new MapSqlParameterSource("tenantId", tenantId));
        adminId = jdbc.queryForObject("""
                INSERT INTO users (tenant_id, email, name, auth_provider, email_verified, roles, created_at, updated_at)
                VALUES (:tenantId, :email, 'Sandbox Admin', 'local', true, '{ADMIN}', now(), now()) RETURNING id
                """, new MapSqlParameterSource("tenantId", tenantId)
                .addValue("email", "admin-" + tenantId + "@sandbox.invalid"), UUID.class);
    }

    @Test
    void guestResponsesNameNoUser() throws Exception {
        String teamId = id(send("POST", "/teams", "{\"name\":\"payments\"}", "ADMIN"));
        send("POST", "/teams/" + teamId + "/members", "{\"userId\":\"" + adminId + "\"}", "ADMIN");
        String serviceId = id(send("POST", "/services", "{\"name\":\"ledger\"}", "ADMIN"));
        send("PUT", "/services/" + serviceId, "{\"name\":\"ledger\",\"description\":\"v2\"}", "ADMIN");

        String members = "/teams/" + teamId + "/members";
        String history = "/services/" + serviceId + "/history";
        String audit = "/audit-events?limit=200";

        assertThat(send("GET", members, null, "VIEWER")).contains(adminId.toString());
        assertThat(send("GET", history, null, "VIEWER")).contains(adminId.toString());
        assertThat(send("GET", audit, null, "VIEWER")).contains(adminId.toString());

        for (String path : new String[] {members, history, audit}) {
            String body = send("GET", path, null, "GUEST");
            assertThat(body).as(path).doesNotContain(adminId.toString()).doesNotContain("@sandbox.invalid");
        }
        assertThat(MAPPER.readTree(send("GET", members, null, "GUEST")).get("data")).hasSize(1);
        assertThat(send("GET", audit, null, "GUEST")).contains("Sandbox Admin").contains("member_added");
    }

    @Test
    void guestAuditFeedIsAnAllowlistNotADenylist() throws Exception {
        insertAudit("future_kind", "created", "{\"name\":\"x\"}");
        insertAudit("scm_connection", "sync_failed",
                "{\"provider\":\"github\",\"error\":\"401 from https://ghe.internal.invalid/api\"}");
        insertAudit("team", "updated",
                "{\"name\":\"payments\",\"invitedEmail\":\"x@sandbox.invalid\","
                        + "\"changes\":{\"name\":{\"from\":\"a\",\"to\":\"payments\"},"
                        + "\"ownerEmail\":{\"from\":\"a@sandbox.invalid\",\"to\":\"b@sandbox.invalid\"}}}");

        String guest = send("GET", "/audit-events?limit=200", null, "GUEST");

        assertThat(guest).doesNotContain("future_kind")
                .doesNotContain("ghe.internal.invalid")
                .doesNotContain("@sandbox.invalid")
                .contains("sync_failed").contains("\"provider\":\"github\"")
                .contains("\"to\":\"payments\"");
        assertThat(send("GET", "/audit-events?limit=200", null, "VIEWER"))
                .contains("future_kind").contains("ghe.internal.invalid").contains("ownerEmail");
    }

    private void insertAudit(String entityType, String action, String payload) {
        jdbc.update("""
                INSERT INTO audit_events (event_id, tenant_id, entity_type, entity_id, action, actor_kind, payload, occurred_at)
                VALUES (gen_random_uuid(), :tenantId, :entityType, gen_random_uuid(), :action, 'system',
                        CAST(:payload AS jsonb), now())
                """, new MapSqlParameterSource("tenantId", tenantId).addValue("entityType", entityType)
                .addValue("action", action).addValue("payload", payload));
    }

    private String send(String method, String path, String body, String roles) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry" + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", ("GUEST".equals(roles) ? UUID.randomUUID() : adminId).toString())
                .header("X-User-Roles", roles)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isLessThan(300);
        return response.body();
    }

    private static String id(String body) {
        return MAPPER.readTree(body).get("data").get("id").asString();
    }
}
