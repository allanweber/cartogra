package io.cartogra.registry;

import io.cartogra.common.audit.ActorKind;
import io.cartogra.common.identity.Actor;
import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.common.event.EventEnvelope;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.web.audit.KafkaAuditEventAdapter;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditEventIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    int port;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    AuditEventPort auditEventPort;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    private UUID tenantId;

    @BeforeEach
    void setUp() throws InterruptedException {
        tenantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO tenants (tenant_id, name, slug, plan_id)
                VALUES (:tenantId, 'Audit Tenant', 'audit-' || :tenantId, (SELECT id FROM billing_plans WHERE slug = 'free'))
                """, new MapSqlParameterSource("tenantId", tenantId));
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    @Test
    void jdbcAdapterRowIsListedWithFilters() throws Exception {
        UUID entityId = UUID.randomUUID();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        auditEventPort.record(new AuditEvent(UUID.randomUUID(), tenantId, "team", entityId, "created",
                ActorKind.SYSTEM, null, Map.of("name", "platform"), at));
        auditEventPort.record(AuditEvent.of(tenantId, "service", UUID.randomUUID(), "created", Actor.SYSTEM, Map.of()));

        JsonNode page = list("?entityType=team&entityId=" + entityId + "&actorKind=system");

        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("entityType").asString()).isEqualTo("team");
        assertThat(item.get("action").asString()).isEqualTo("created");
        assertThat(item.get("actorKind").asString()).isEqualTo("system");
        assertThat(item.get("payload").get("name").asString()).isEqualTo("platform");
        assertThat(Instant.parse(item.get("occurredAt").asString())).isEqualTo(at);
        assertThat(list("").get("total").asLong()).isEqualTo(2);
        assertThat(list("?entityType=team,service").get("total").asLong()).isEqualTo(2);
        assertThat(list("?from=" + Instant.now().plusSeconds(60)).get("total").asLong()).isZero();
    }

    @Test
    void sameEventIdConsumedTwiceStoresOneRow() throws Exception {
        AuditEvent event = AuditEvent.of(tenantId, "dependency", UUID.randomUUID(), "declared",
                Actor.user(UUID.randomUUID()), Map.of("protocol", "http"));
        String json = objectMapper.writeValueAsString(
                EventEnvelope.of(KafkaAuditEventAdapter.EVENT_TYPE, event.entityId(), tenantId, 1, event));
        var template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));

        template.send(new ProducerRecord<>(KafkaAuditEventAdapter.TOPIC, event.entityId().toString(), json)).get();
        template.send(new ProducerRecord<>(KafkaAuditEventAdapter.TOPIC, event.entityId().toString(), json)).get();
        UUID marker = UUID.randomUUID();
        AuditEvent after = AuditEvent.of(tenantId, "dependency", marker, "declared", Actor.SYSTEM, Map.of());
        template.send(new ProducerRecord<>(KafkaAuditEventAdapter.TOPIC, marker.toString(),
                objectMapper.writeValueAsString(
                        EventEnvelope.of(KafkaAuditEventAdapter.EVENT_TYPE, marker, tenantId, 1, after)))).get();

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (rows("entity_id", marker) == 0 && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertThat(rows("entity_id", marker)).as("later marker event consumed").isEqualTo(1);
        assertThat(rows("event_id", event.eventId())).isEqualTo(1);
    }

    @Test
    void creatingAServiceRecordsExactlyOneAuditRow() throws Exception {
        UUID userId = UUID.randomUUID();
        var response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/services"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", userId.toString())
                .header("X-User-Roles", "MEMBER")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"audited-svc\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);
        UUID serviceId = UUID.fromString(objectMapper.readTree(response.body()).get("data").get("id").asString());

        JsonNode page = list("?entityType=service&entityId=" + serviceId);

        assertThat(page.get("total").asLong()).isEqualTo(1);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("action").asString()).isEqualTo("created");
        assertThat(item.get("actorKind").asString()).isEqualTo("user");
        assertThat(item.get("actorId").asString()).isEqualTo(userId.toString());
        assertThat(item.get("payload").get("name").asString()).isEqualTo("audited-svc");
    }

    private long rows(String column, UUID value) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE " + column + " = :v",
                new MapSqlParameterSource("v", value), Long.class);
        return n == null ? 0 : n;
    }

    private JsonNode list(String query) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/audit-events" + query))
                .header("X-Tenant-Id", tenantId.toString())
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body()).get("data");
    }
}
