package io.cartogra.registry;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.event.OwnershipResolvedPayload;
import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                "cartogra.ingestion.ownership.resolved",
                "cartogra.registry.service.registered",
                "cartogra.registry.service.updated",
                "cartogra.registry.service.deleted"
        }
)
class CodeownersFlowIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @Test
    void ownershipResolved_autoAssignsTeamToService() throws Exception {
        UUID tenantId = UUID.randomUUID();

        // Create team
        HttpResponse<String> teamResp = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"platform-team"}""", tenantId),
                HttpResponse.BodyHandlers.ofString());
        assertThat(teamResp.statusCode()).isEqualTo(201);
        String teamId = objectMapper.readTree(teamResp.body()).get("data").get("id").textValue();

        // Create service with a repositoryUrl that contains the full path
        HttpResponse<String> svcResp = HTTP.send(
                post("/api/v1/services", """
                        {"name":"codeowners-test-svc","repositoryUrl":"https://github.com/acme/platform-api"}""", tenantId),
                HttpResponse.BodyHandlers.ofString());
        assertThat(svcResp.statusCode()).isEqualTo(201);
        String serviceId = objectMapper.readTree(svcResp.body()).get("data").get("id").textValue();

        // Verify service has no team yet
        JsonNode svcData = objectMapper.readTree(
                HTTP.send(get("/api/v1/services/" + serviceId, tenantId), HttpResponse.BodyHandlers.ofString()).body())
                .get("data");
        JsonNode initialTeamId = svcData.get("teamId");
        assertThat(initialTeamId == null || initialTeamId.isNull()).isTrue();

        // Publish synthetic ownership.resolved event
        var payload = new OwnershipResolvedPayload(
                tenantId, UUID.randomUUID(), "acme/platform-api",
                List.of("platform-team"), Map.of());
        var envelope = EventEnvelope.of("ownership.resolved", UUID.fromString(serviceId), tenantId, 1, payload);
        String json = objectMapper.writeValueAsString(envelope);

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        var template = new KafkaTemplate<>(producerFactory);
        template.send(new ProducerRecord<>("cartogra.ingestion.ownership.resolved", "acme/platform-api", json));
        template.flush();

        // Wait for consumer to process and assert teamId is set
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    HttpResponse<String> check = HTTP.send(
                            get("/api/v1/services/" + serviceId, tenantId),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(check.statusCode()).isEqualTo(200);
                    JsonNode updated = objectMapper.readTree(check.body()).get("data");
                    assertThat(updated.get("teamId")).isNotNull();
                    assertThat(updated.get("teamId").textValue()).isEqualTo(teamId);
                });
    }

    private HttpRequest post(String path, String body, UUID tenantId) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest get(String path, UUID tenantId) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenantId.toString())
                .GET()
                .build();
    }
}
