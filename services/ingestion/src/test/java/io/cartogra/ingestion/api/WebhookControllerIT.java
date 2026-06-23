package io.cartogra.ingestion.api;

import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full HTTP → signature verification → Kafka path for the public webhook receiver. The scheduler
 * tick is parked (PT999H) so the only sync command on the topic is the one this test triggers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {"cartogra.registry.sync.command"}
)
class WebhookControllerIT {

    private static final String SECRET = "topsecret";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.flyway.schemas", () -> "ingestion");
        registry.add("spring.flyway.default-schema", () -> "ingestion");
        registry.add("ingestion.workers.k8s.enabled", () -> "false");
        registry.add("ingestion.sync.poll-interval", () -> "PT999H");
    }

    @LocalServerPort
    int port;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void validPushSignaturePublishesSyncCommand() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = seedConnection(tenantId, true);
        seedWebhook(tenantId, connectionId, SECRET);

        byte[] body = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> resp = post(connectionId, body,
                Map.of("X-GitHub-Event", "push", "X-Hub-Signature-256", sign(SECRET, body)));

        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(keysOnTopic()).containsOnlyOnce(connectionId.toString());
    }

    @Test
    void tamperedSignatureReturns401AndPublishesNothing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = seedConnection(tenantId, true);
        seedWebhook(tenantId, connectionId, SECRET);

        byte[] body = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> resp = post(connectionId, body,
                Map.of("X-GitHub-Event", "push", "X-Hub-Signature-256", "sha256=deadbeef"));

        assertThat(resp.statusCode()).isEqualTo(401);
        assertThat(keysOnTopic()).doesNotContain(connectionId.toString());
    }

    @Test
    void pingEventIsAcceptedButPublishesNothing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = seedConnection(tenantId, true);
        seedWebhook(tenantId, connectionId, SECRET);

        byte[] body = "{\"zen\":\"Keep it simple.\"}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> resp = post(connectionId, body,
                Map.of("X-GitHub-Event", "ping", "X-Hub-Signature-256", sign(SECRET, body)));

        assertThat(resp.statusCode()).isEqualTo(202);
        assertThat(keysOnTopic()).doesNotContain(connectionId.toString());
    }

    @Test
    void unknownConnectionReturns404() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> resp = post(UUID.randomUUID(), body,
                Map.of("X-GitHub-Event", "push", "X-Hub-Signature-256", sign(SECRET, body)));

        assertThat(resp.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> post(UUID connectionId, byte[] body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/webhooks/github/" + connectionId))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private UUID seedConnection(UUID tenantId, boolean webhookEnabled) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO scm_connections (id, tenant_id, provider, config, webhook_enabled, created_at, updated_at)
                VALUES (:id, :tenantId, 'github', CAST(:config AS jsonb), :webhookEnabled, :now, :now)
                """, new MapSqlParameterSource(Map.of(
                "id", id,
                "tenantId", tenantId,
                "config", "{\"token\":\"t\",\"org\":\"test-org\"}",
                "webhookEnabled", webhookEnabled,
                "now", Timestamp.from(now))));
        return id;
    }

    private void seedWebhook(UUID tenantId, UUID connectionId, String secret) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO scm_webhooks (id, tenant_id, scm_connection_id, provider, target_url,
                    webhook_secret, events, status, created_at, updated_at)
                VALUES (:id, :tenantId, :connectionId, 'github', :targetUrl, :secret,
                    ARRAY['push'], 'active', :now, :now)
                """, new MapSqlParameterSource(Map.of(
                "id", UUID.randomUUID(),
                "tenantId", tenantId,
                "connectionId", connectionId,
                "targetUrl", "http://localhost/webhooks/github/" + connectionId,
                "secret", secret,
                "now", Timestamp.from(now))));
    }

    private static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Drains the shared topic for a fixed window and returns the message keys. The embedded broker
     * is shared across test methods, so assertions key off this test's connection id rather than
     * global emptiness.
     */
    private List<String> keysOnTopic() {
        List<String> keys = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ))) {
            consumer.subscribe(List.of("cartogra.registry.sync.command"));
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> keys.add(r.key()));
            }
        }
        return keys;
    }
}
