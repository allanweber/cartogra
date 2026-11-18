package io.cartogra.contract.api;

import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.ContractHttp;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.web.audit.KafkaAuditEventAdapter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ContractControllerIT extends AbstractContractIT {

    static final String V1 = """
            openapi: 3.0.3
            info:
              title: Ledger
              version: 1.0.0
            paths:
              /entries:
                get:
                  responses:
                    '200':
                      description: ok
            """;

    static final String V1_JSON = """
            {"paths":{"/entries":{"get":{"responses":{"200":{"description":"ok"}}}}},
             "info":{"version":"1.0.0","title":"Ledger"},"openapi":"3.0.3"}
            """;

    static final String V2 = V1.replace("version: 1.0.0", "version: 1.1.0");

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    ContractHttp http;
    UUID tenant;

    @BeforeEach
    void setUp() {
        http = new ContractHttp(port);
        tenant = UUID.randomUUID();
    }

    @Test
    void createReturnsContractWithVersionOne() {
        UUID serviceId = UUID.randomUUID();

        HttpResponse<String> resp = http.create(tenant, serviceId, V1);

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode data = data(resp);
        assertThat(data.get("serviceId").stringValue()).isEqualTo(serviceId.toString());
        assertThat(data.get("specType").stringValue()).isEqualTo("openapi");
        JsonNode version = data.get("latestVersion");
        assertThat(version.get("versionNumber").intValue()).isEqualTo(1);
        assertThat(version.get("label").stringValue()).isEqualTo("1.0.0");
        assertThat(version.get("specHash").stringValue()).matches("[0-9a-f]{64}");
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void createForServiceRegistryDoesNotKnowIs404AndStoresNothing() {
        org.mockito.Mockito.when(registryNameClient.findNames(org.mockito.ArgumentMatchers.eq(tenant),
                org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());

        HttpResponse<String> resp = http.create(tenant, UUID.randomUUID(), V1);

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_contracts WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class)).isZero();
    }

    @Test
    void createWhenRegistryIsDownIs503AndStoresNothing() {
        org.mockito.Mockito.when(registryNameClient.findNames(org.mockito.ArgumentMatchers.eq(tenant),
                org.mockito.ArgumentMatchers.any())).thenThrow(new org.springframework.web.client.RestClientException("down"));

        HttpResponse<String> resp = http.create(tenant, UUID.randomUUID(), V1);

        assertThat(resp.statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_contracts WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant), Long.class)).isZero();
    }

    @Test
    void createWithTrailingSlashAlsoWorks() {
        HttpResponse<String> resp = http.post("/", tenant, "ADMIN", ContractHttp.body(UUID.randomUUID(), V1));

        assertThat(resp.statusCode()).isEqualTo(201);
    }

    @Test
    void secondCreateForServiceConflictsWithExistingId() {
        UUID serviceId = UUID.randomUUID();
        String id = id(http.create(tenant, serviceId, V1));

        HttpResponse<String> resp = http.create(tenant, serviceId, V2);

        assertThat(resp.statusCode()).isEqualTo(409);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("CONFLICT");
        assertThat(error.get("details").get("contractId").stringValue()).isEqualTo(id);
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void createWithoutServiceIdIsValidationError() {
        HttpResponse<String> resp = http.post("", tenant, "ADMIN", ContractHttp.body(null, V1));

        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(errorCode(resp)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void sameSpecInJsonWithReorderedKeysIsTheLatestVersion() {
        HttpResponse<String> created = http.create(tenant, UUID.randomUUID(), V1);
        String id = id(created);

        HttpResponse<String> resp = http.publish(tenant, id, V1_JSON);

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(data(resp).get("versionNumber").intValue()).isEqualTo(1);
        assertThat(data(resp).get("specHash").stringValue())
                .isEqualTo(data(created).get("latestVersion").get("specHash").stringValue());
        assertThat(versionRows(id)).isEqualTo(1);
    }

    @Test
    void changedSpecIsNextVersionAndRevertIsANewOne() {
        String id = id(http.create(tenant, UUID.randomUUID(), V1));

        HttpResponse<String> v2 = http.publish(tenant, id, V2);
        HttpResponse<String> revert = http.publish(tenant, id, V1);

        assertThat(v2.statusCode()).isEqualTo(201);
        assertThat(data(v2).get("versionNumber").intValue()).isEqualTo(2);
        assertThat(data(v2).get("label").stringValue()).isEqualTo("1.1.0");
        assertThat(revert.statusCode()).isEqualTo(201);
        assertThat(data(revert).get("versionNumber").intValue()).isEqualTo(3);
        assertThat(data(http.get(tenant, id)).get("latestVersion").get("versionNumber").intValue()).isEqualTo(3);
    }

    @Test
    void concurrentPublishesNeverShareAVersionNumber() throws Exception {
        String id = id(http.create(tenant, UUID.randomUUID(), V1));
        List<String> specs = List.of(V1.replace("/entries:", "/a:"), V1.replace("/entries:", "/b:"),
                V1.replace("/entries:", "/c:"), V1.replace("/entries:", "/d:"));

        List<Integer> numbers = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> futures = specs.stream()
                    .map(spec -> executor.submit(() -> http.publish(tenant, id, spec)))
                    .toList();
            for (var future : futures) {
                HttpResponse<String> resp = future.get();
                assertThat(resp.statusCode()).isEqualTo(201);
                numbers.add(data(resp).get("versionNumber").intValue());
            }
        }

        assertThat(numbers).containsExactlyInAnyOrder(2, 3, 4, 5);
    }

    @Test
    void invalidSpecsAreUnprocessableWithMessages() {
        for (String spec : List.of(
                "openapi: [unclosed",
                "swagger: '2.0'\ninfo: {title: x, version: '1'}\npaths: {}\n",
                "openapi: 3.0.3\n")) {
            HttpResponse<String> resp = http.create(tenant, UUID.randomUUID(), spec);

            assertThat(resp.statusCode()).as(spec).isEqualTo(422);
            JsonNode error = objectMapper.readTree(resp.body()).get("error");
            assertThat(error.get("code").stringValue()).isEqualTo("INVALID_SPEC");
            assertThat(error.get("details").get("messages").isEmpty()).isFalse();
            assertTraceIdHeaderMatchesBody(resp);
        }
    }

    @Test
    void blankOrOversizedSpecIsValidationError() {
        String oversized = V1 + "x-pad: " + "a".repeat(1_048_576) + "\n";

        assertThat(errorCode(http.create(tenant, UUID.randomUUID(), "  "))).isEqualTo("VALIDATION_ERROR");
        HttpResponse<String> big = http.create(tenant, UUID.randomUUID(), oversized);
        assertThat(big.statusCode()).isEqualTo(400);
        assertThat(errorCode(big)).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void otherTenantsContractIsNotFound() {
        String id = id(http.create(tenant, UUID.randomUUID(), V1));

        HttpResponse<String> resp = http.get(UUID.randomUUID(), id);

        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(errorCode(resp)).isEqualTo("NOT_FOUND");
        assertThat(http.publish(UUID.randomUUID(), id, V2).statusCode()).isEqualTo(404);
    }

    @Test
    void eachCreateAndPublishedVersionEmitsOneAuditEvent() {
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "contract-audit-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(KafkaAuditEventAdapter.TOPIC));

            String id = id(http.create(tenant, UUID.randomUUID(), V1));
            http.publish(tenant, id, V2);
            http.publish(tenant, id, V2);

            List<JsonNode> events = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && events.size() < 2) {
                collect(consumer, id, events, Duration.ofMillis(300));
            }
            collect(consumer, id, events, Duration.ofSeconds(1));

            assertThat(events).extracting(e -> e.get("action").stringValue())
                    .containsExactlyInAnyOrder("created", "version_published");
            assertThat(events).allSatisfy(e -> {
                assertThat(e.get("entityType").stringValue()).isEqualTo("contract");
                assertThat(e.get("actorKind").stringValue()).isEqualTo("USER");
                assertThat(e.get("payload").has("spec")).isFalse();
            });
        }
    }

    private void collect(KafkaConsumer<String, String> consumer, String id, List<JsonNode> events, Duration poll) {
        for (ConsumerRecord<String, String> record : consumer.poll(poll)) {
            if (id.equals(record.key())) {
                events.add(objectMapper.readTree(record.value()).get("payload"));
            }
        }
    }

    private int versionRows(String contractId) {
        return jdbc.queryForObject("SELECT count(*) FROM contract_versions WHERE contract_id = :id",
                new MapSqlParameterSource("id", UUID.fromString(contractId)), Integer.class);
    }

    private JsonNode data(HttpResponse<String> resp) {
        return objectMapper.readTree(resp.body()).get("data");
    }

    private String id(HttpResponse<String> resp) {
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return data(resp).get("id").stringValue();
    }

    private String errorCode(HttpResponse<String> resp) {
        return objectMapper.readTree(resp.body()).get("error").get("code").stringValue();
    }

    private void assertTraceIdHeaderMatchesBody(HttpResponse<String> resp) {
        String header = resp.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(header).matches("[0-9a-f]{32}");
        assertThat(objectMapper.readTree(resp.body()).get("traceId").stringValue()).isEqualTo(header);
    }
}
