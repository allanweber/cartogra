package io.cartogra.registry;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.Team;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TeamCrudIT {

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
                ConsumerConfig.GROUP_ID_CONFIG, "test-team-crud-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumer = new KafkaConsumer<>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer());
        consumer.subscribe(List.of(
                "cartogra.registry.team.created",
                "cartogra.registry.team.updated",
                "cartogra.registry.team.deleted"));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void createReadUpdateDelete() throws Exception {
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"platform-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode data = objectMapper.readTree(created.body()).get("data");
        String teamId = data.get("id").asText();
        assertThat(data.get("name").asText()).isEqualTo("platform-team");
        assertThat(created.headers().firstValue("X-Trace-Id")).isPresent();

        ConsumerRecord<String, String> createdRecord = pollForRecord("cartogra.registry.team.created", teamId);
        assertThat(createdRecord).isNotNull();
        assertTraceparent(createdRecord);
        EventEnvelope<Team> createdEnvelope = objectMapper.readValue(
                createdRecord.value(), new TypeReference<EventEnvelope<Team>>() {});
        assertThat(createdEnvelope.eventType()).isEqualTo("team.created");
        assertThat(createdEnvelope.entityId()).isEqualTo(UUID.fromString(teamId));
        assertThat(createdEnvelope.tenantId()).isEqualTo(TENANT);
        assertThat(createdEnvelope.payload().name()).isEqualTo("platform-team");
        assertThat(createdEnvelope.payload().deletedAt()).isNull();

        HttpResponse<String> got = HTTP.send(
                get("/api/v1/teams/" + teamId), HttpResponse.BodyHandlers.ofString());
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(got.body()).get("data").get("name").asText()).isEqualTo("platform-team");

        HttpResponse<String> updated = HTTP.send(
                put("/api/v1/teams/" + teamId, """
                        {"name":"platform-engineering"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(updated.body()).get("data").get("name").asText()).isEqualTo("platform-engineering");

        ConsumerRecord<String, String> updatedRecord = pollForRecord("cartogra.registry.team.updated", teamId);
        assertThat(updatedRecord).isNotNull();
        assertTraceparent(updatedRecord);
        EventEnvelope<Team> updatedEnvelope = objectMapper.readValue(
                updatedRecord.value(), new TypeReference<EventEnvelope<Team>>() {});
        assertThat(updatedEnvelope.eventType()).isEqualTo("team.updated");
        assertThat(updatedEnvelope.entityId()).isEqualTo(UUID.fromString(teamId));
        assertThat(updatedEnvelope.payload().name()).isEqualTo("platform-engineering");

        HttpResponse<String> deleted = HTTP.send(
                delete("/api/v1/teams/" + teamId), HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(204);

        ConsumerRecord<String, String> deletedRecord = pollForRecord("cartogra.registry.team.deleted", teamId);
        assertThat(deletedRecord).isNotNull();
        assertTraceparent(deletedRecord);
        EventEnvelope<Team> deletedEnvelope = objectMapper.readValue(
                deletedRecord.value(), new TypeReference<EventEnvelope<Team>>() {});
        assertThat(deletedEnvelope.eventType()).isEqualTo("team.deleted");
        assertThat(deletedEnvelope.entityId()).isEqualTo(UUID.fromString(teamId));
        assertThat(deletedEnvelope.payload().deletedAt()).isNotNull();

        HttpResponse<String> gone = HTTP.send(
                get("/api/v1/teams/" + teamId), HttpResponse.BodyHandlers.ofString());
        assertThat(gone.statusCode()).isEqualTo(404);
    }

    @Test
    void listTeams() throws Exception {
        HTTP.send(post("/api/v1/teams", """
                {"name":"list-team-alpha"}"""), HttpResponse.BodyHandlers.ofString());
        HTTP.send(post("/api/v1/teams", """
                {"name":"list-team-beta"}"""), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> list = HTTP.send(
                get("/api/v1/teams?limit=50&offset=0"), HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode result = objectMapper.readTree(list.body()).get("data");
        assertThat(result.get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(result.get("items").isArray()).isTrue();
    }

    @Test
    void missingTeamReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                get("/api/v1/teams/" + UUID.randomUUID()), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void updateNonExistentTeamReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                put("/api/v1/teams/" + UUID.randomUUID(), """
                        {"name":"ghost-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void deleteNonExistentTeamReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                delete("/api/v1/teams/" + UUID.randomUUID()), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(objectMapper.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void duplicateTeamNameReturns409() throws Exception {
        HTTP.send(post("/api/v1/teams", """
                {"name":"dup-team-x"}"""), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> dup = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"dup-team-x"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(dup.body()).get("error").get("code").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void renameToExistingTeamNameReturns409() throws Exception {
        HTTP.send(post("/api/v1/teams", """
                {"name":"rename-target-team"}"""), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"rename-source-team"}"""), HttpResponse.BodyHandlers.ofString());
        String secondId = objectMapper.readTree(second.body()).get("data").get("id").asText();

        HttpResponse<String> resp = HTTP.send(
                put("/api/v1/teams/" + secondId, """
                        {"name":"rename-target-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void blankTeamNameReturns400() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                post("/api/v1/teams", """
                        {"name":""}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(objectMapper.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("VALIDATION_ERROR");
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
