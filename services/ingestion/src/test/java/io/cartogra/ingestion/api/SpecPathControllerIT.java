package io.cartogra.ingestion.api;

import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.web.audit.KafkaAuditEventAdapter;
import io.cartogra.web.security.InternalTokens;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpecPathControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @MockitoBean
    SyncCommandConsumer syncCommandConsumer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", () -> PostgresTestSupport.APP_USER);
        registry.add("spring.datasource.password", () -> PostgresTestSupport.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> PostgresTestSupport.OWNER_USER);
        registry.add("spring.flyway.password", () -> PostgresTestSupport.OWNER_PASSWORD);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void getGivesTheFiveDefaultsAndNoCustomPathsForANewTenant() throws Exception {
        UUID tenantId = UUID.randomUUID();

        HttpResponse<String> resp = send("GET", tenantId, "GUEST", null);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("X-Trace-Id")).isPresent();
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("defaults").size()).isEqualTo(5);
        assertThat(data.get("defaults").get(0).stringValue()).isEqualTo("openapi.yaml");
        assertThat(data.get("custom").size()).isZero();
    }

    @Test
    void adminPutReplacesTheCustomPathsAndPublishesOneAuditEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "spec-paths-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaAuditEventAdapter.TOPIC));

            HttpResponse<String> put = send("PUT", tenantId, userId, "ADMIN",
                    "{\"custom\":[\"src/main/resources/openapi.yml\"]}");
            assertThat(put.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(put.body()).get("data").get("custom").get(0).stringValue())
                    .isEqualTo("src/main/resources/openapi.yml");

            JsonNode get = objectMapper.readTree(send("GET", tenantId, "VIEWER", null).body()).get("data");
            assertThat(get.get("custom").get(0).stringValue()).isEqualTo("src/main/resources/openapi.yml");

            List<String> events = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && events.isEmpty()) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (record.value().contains(tenantId.toString()) && record.value().contains("spec_paths")) {
                        events.add(record.value());
                    }
                }
            }
            assertThat(events).hasSize(1);
            JsonNode event = objectMapper.readTree(events.getFirst()).get("payload");
            assertThat(event.get("entityType").stringValue()).isEqualTo("spec_paths");
            assertThat(event.get("action").stringValue()).isEqualTo("updated");
            assertThat(event.get("actorId").stringValue()).isEqualTo(userId.toString());
        }
    }

    @Test
    void guestPutIsForbidden() throws Exception {
        assertThat(send("PUT", UUID.randomUUID(), "GUEST", "{\"custom\":[\"spec/api.yaml\"]}").statusCode())
                .isEqualTo(403);
    }

    @Test
    void invalidPathsGive400WithTheReasons() throws Exception {
        HttpResponse<String> resp = send("PUT", UUID.randomUUID(), "ADMIN",
                "{\"custom\":[\"/abs.yaml\",\"specs/*.yaml\",\"openapi.yaml\"]}");

        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.get("details").size()).isEqualTo(3);
    }

    private HttpResponse<String> send(String method, UUID tenantId, String role, String body) throws Exception {
        return send(method, tenantId, UUID.randomUUID(), role, body);
    }

    private HttpResponse<String> send(String method, UUID tenantId, UUID userId, String role, String body)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/ingestion/spec-paths"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .header(InternalTokens.HEADER, InternalTokens.token("ingestion", tenantId, userId, role))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }
}
