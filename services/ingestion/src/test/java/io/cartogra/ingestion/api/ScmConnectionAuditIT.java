package io.cartogra.ingestion.api;

import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimitClient;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.web.audit.KafkaAuditEventAdapter;
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
class ScmConnectionAuditIT {

    @MockitoBean
    SyncCommandProducer syncCommandProducer;

    @MockitoBean
    SyncCommandConsumer syncCommandConsumer;

    @MockitoBean
    RegistryPlanLimitClient planLimitClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void creatingAConnectionPublishesOneAuditEventWithoutTheToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "audit-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaAuditEventAdapter.TOPIC));

            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/api/v1/ingestion/scm-connections"))
                    .header("Content-Type", "application/json")
                    .header("X-Tenant-Id", tenantId.toString())
                    .header("X-User-Id", userId.toString())
                    .header("X-User-Roles", "ADMIN")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"provider":"github","config":"{\\"org\\":\\"acme\\",\\"token\\":\\"ghp_supersecret\\"}"}
                            """))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            String connectionId = objectMapper.readTree(response.body()).get("data").get("id").stringValue();

            List<String> events = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && events.isEmpty()) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    if (connectionId.equals(record.key())) events.add(record.value());
                }
            }
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofSeconds(1))) {
                if (connectionId.equals(record.key())) events.add(record.value());
            }

            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).doesNotContain("ghp_supersecret", "token", "config");
            JsonNode event = objectMapper.readTree(events.getFirst()).get("payload");
            assertThat(event.get("entityType").stringValue()).isEqualTo("scm_connection");
            assertThat(event.get("action").stringValue()).isEqualTo("created");
            assertThat(event.get("actorId").stringValue()).isEqualTo(userId.toString());
            assertThat(event.get("payload").get("provider").stringValue()).isEqualTo("github");
        }
    }
}
