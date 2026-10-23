package io.cartogra.topology.api;

import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.topology.repository.UnresolvedSpanServiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "topology.graph-view.refresh-interval=PT1H",
        "topology.drift.sweep-interval=PT1H"})
class UnresolvedSpanServiceControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "unresolved-controller-it-" + UUID.randomUUID());
    }

    @LocalServerPort
    private int port;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private UnresolvedSpanServiceRepository repository;

    private HttpResponse<String> send(String method, String path, UUID tenantId) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/unresolved-span-services" + path))
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", UUID.randomUUID().toString());
        HttpRequest request = "POST".equals(method)
                ? builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                : builder.GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void seen(UUID tenant, String name, int times) {
        for (int i = 0; i < times; i++) {
            repository.record(tenant, name, Instant.now(), 100);
        }
    }

    private String idOf(UUID tenant, String name) {
        return repository.findActive(tenant, 200, 0).stream()
                .filter(r -> r.name().equals(name)).findFirst().orElseThrow().id().toString();
    }

    @Test
    void listReturnsLiveRowsBySeenCountInTheEnvelope() throws Exception {
        UUID tenant = UUID.randomUUID();
        seen(tenant, "legacy-ldap", 1);
        seen(tenant, "stripe-gateway", 3);
        seen(UUID.randomUUID(), "elsewhere", 5);

        HttpResponse<String> resp = send("GET", "?limit=1&offset=0", tenant);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("traceId").stringValue()).hasSize(32);
        assertThat(resp.headers().firstValue("X-Trace-Id")).hasValue(body.get("traceId").stringValue());
        JsonNode data = body.get("data");
        assertThat(data.get("total").longValue()).isEqualTo(2);
        assertThat(data.get("limit").intValue()).isEqualTo(1);
        assertThat(data.get("items")).hasSize(1);
        JsonNode item = data.get("items").get(0);
        assertThat(item.get("name").stringValue()).isEqualTo("stripe-gateway");
        assertThat(item.get("seenCount").longValue()).isEqualTo(3);
        assertThat(item.get("resolution").isNull()).isTrue();
    }

    @Test
    void dismissRemovesTheRowFromTheList() throws Exception {
        UUID tenant = UUID.randomUUID();
        seen(tenant, "stripe-gateway", 1);
        String id = idOf(tenant, "stripe-gateway");

        HttpResponse<String> resp = send("POST", "/" + id + "/dismiss", tenant);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode item = objectMapper.readTree(resp.body()).get("data");
        assertThat(item.get("resolution").stringValue()).isEqualTo("DISMISSED");
        assertThat(item.get("resolvedAt").isNull()).isFalse();
        JsonNode list = objectMapper.readTree(send("GET", "", tenant).body()).get("data");
        assertThat(list.get("total").longValue()).isZero();
    }

    @Test
    void dismissingTwiceIs409() throws Exception {
        UUID tenant = UUID.randomUUID();
        seen(tenant, "stripe-gateway", 1);
        String id = idOf(tenant, "stripe-gateway");
        send("POST", "/" + id + "/dismiss", tenant);

        HttpResponse<String> resp = send("POST", "/" + id + "/dismiss", tenant);

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(resp.body()).get("error").get("code").stringValue())
                .isEqualTo("UNRESOLVED_SPAN_SERVICE_ALREADY_RESOLVED");
    }

    @Test
    void unknownAndCrossTenantIdsAre404() throws Exception {
        UUID tenant = UUID.randomUUID();
        seen(tenant, "stripe-gateway", 1);
        String id = idOf(tenant, "stripe-gateway");

        assertThat(send("POST", "/" + UUID.randomUUID() + "/dismiss", tenant).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/" + id + "/dismiss", UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(repository.countActive(tenant)).isEqualTo(1);
    }
}
