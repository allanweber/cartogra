package io.cartogra.registry;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.Service;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.core.type.TypeReference;
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
class ServiceCrudIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final UUID TENANT = UUID.randomUUID();

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @LocalServerPort
    private int port;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setupConsumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-crud-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumer = new KafkaConsumer<>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer());
        consumer.subscribe(List.of(
                "cartogra.registry.service.registered",
                "cartogra.registry.service.updated",
                "cartogra.registry.service.deleted"));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void createReadUpdateDelete() throws Exception {
        // CREATE
        String body = """
                {"name":"payments-svc","description":"handles payments"}
                """;
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", body), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode data = objectMapper.readTree(created.body()).get("data");
        String serviceId = data.get("id").asText();
        assertThat(data.get("name").asText()).isEqualTo("payments-svc");
        assertThat(created.headers().firstValue("X-Trace-Id")).isPresent();

        ConsumerRecord<String, String> registeredRecord = pollForRecord("cartogra.registry.service.registered", serviceId);
        assertThat(registeredRecord).isNotNull();
        assertTraceparent(registeredRecord);
        EventEnvelope<Service> registeredEnvelope = objectMapper.readValue(
                registeredRecord.value(),
                new TypeReference<EventEnvelope<Service>>() {});
        assertThat(registeredEnvelope.eventType()).isEqualTo("service.registered");
        assertThat(registeredEnvelope.entityId()).isEqualTo(UUID.fromString(serviceId));
        assertThat(registeredEnvelope.tenantId()).isEqualTo(TENANT);
        assertThat(registeredEnvelope.payload().name()).isEqualTo("payments-svc");
        assertThat(registeredEnvelope.payload().deletedAt()).isNull();

        // READ
        HttpResponse<String> got = HTTP.send(
                get("/api/v1/services/" + serviceId), HttpResponse.BodyHandlers.ofString());
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(got.body()).get("data").get("name").asText()).isEqualTo("payments-svc");

        // UPDATE
        String updateBody = """
                {"name":"payments-service","description":"handles payments v2","healthStatus":"healthy"}
                """;
        HttpResponse<String> updated = HTTP.send(
                put("/api/v1/services/" + serviceId, updateBody), HttpResponse.BodyHandlers.ofString());
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(updated.body()).get("data").get("name").asText()).isEqualTo("payments-service");

        ConsumerRecord<String, String> updatedRecord = pollForRecord("cartogra.registry.service.updated", serviceId);
        assertThat(updatedRecord).isNotNull();
        assertTraceparent(updatedRecord);
        EventEnvelope<Service> updatedEnvelope = objectMapper.readValue(
                updatedRecord.value(),
                new TypeReference<EventEnvelope<Service>>() {});
        assertThat(updatedEnvelope.eventType()).isEqualTo("service.updated");
        assertThat(updatedEnvelope.entityId()).isEqualTo(UUID.fromString(serviceId));
        assertThat(updatedEnvelope.payload().name()).isEqualTo("payments-service");

        // DELETE
        HttpResponse<String> deleted = HTTP.send(
                delete("/api/v1/services/" + serviceId), HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(204);

        ConsumerRecord<String, String> deletedRecord = pollForRecord("cartogra.registry.service.deleted", serviceId);
        assertThat(deletedRecord).isNotNull();
        assertTraceparent(deletedRecord);
        EventEnvelope<Service> deletedEnvelope = objectMapper.readValue(
                deletedRecord.value(),
                new TypeReference<EventEnvelope<Service>>() {});
        assertThat(deletedEnvelope.eventType()).isEqualTo("service.deleted");
        assertThat(deletedEnvelope.payload().deletedAt()).isNotNull();

        // 404 after delete
        HttpResponse<String> gone = HTTP.send(
                get("/api/v1/services/" + serviceId), HttpResponse.BodyHandlers.ofString());
        assertThat(gone.statusCode()).isEqualTo(404);
    }

    @Test
    void listServices() throws Exception {
        HTTP.send(post("/api/v1/services", """
                {"name":"list-test-svc-1"}"""), HttpResponse.BodyHandlers.ofString());
        HTTP.send(post("/api/v1/services", """
                {"name":"list-test-svc-2"}"""), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> list = HTTP.send(
                get("/api/v1/services?limit=50&offset=0"), HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode result = objectMapper.readTree(list.body()).get("data");
        assertThat(result.get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(result.get("items").isArray()).isTrue();
    }

    @Test
    void techStackRoundTrip() throws Exception {
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/services", """
                        {"name":"ts-roundtrip-svc","techStack":["java","kotlin"]}"""),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode createData = objectMapper.readTree(created.body()).get("data");
        String svcId = createData.get("id").asText();

        JsonNode createTs = createData.get("techStack");
        assertThat(createTs.isArray()).isTrue();
        List<String> createdStacks = new ArrayList<>();
        createTs.forEach(n -> createdStacks.add(n.textValue()));
        assertThat(createdStacks).containsExactlyInAnyOrder("java", "kotlin");

        HttpResponse<String> got = HTTP.send(
                get("/api/v1/services/" + svcId), HttpResponse.BodyHandlers.ofString());
        assertThat(got.statusCode()).isEqualTo(200);
        JsonNode readTs = objectMapper.readTree(got.body()).get("data").get("techStack");
        assertThat(readTs.isArray()).isTrue();
        List<String> readStacks = new ArrayList<>();
        readTs.forEach(n -> readStacks.add(n.textValue()));
        assertThat(readStacks).containsExactlyInAnyOrder("java", "kotlin");
    }

    @Test
    void missingServiceReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                get("/api/v1/services/" + UUID.randomUUID()), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").asText()).isEqualTo("NOT_FOUND");
    }

    private ConsumerRecord<String, String> pollForRecord(String topic, String key) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            var records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (record.topic().equals(topic) && key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    private void assertTraceparent(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("traceparent");
        assertThat(header).as("traceparent header must be present").isNotNull();
        assertThat(new String(header.value())).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT.toString())
                .GET()
                .build();
    }

    private HttpRequest put(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest delete(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT.toString())
                .DELETE()
                .build();
    }
}
