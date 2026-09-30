package io.cartogra.topology.api;

import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.CycleService;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage of {@code GET /cycles}: real Postgres, real recursive CTE over
 * {@code dependency_graph_edges}. Mirrors {@link BlastRadiusControllerIT}'s setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "topology.graph-view.refresh-interval=PT1H")
class CycleControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> io.cartogra.test.PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", io.cartogra.test.PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", io.cartogra.test.PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", io.cartogra.test.KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "cycle-controller-it-" + UUID.randomUUID());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphNodeRepository graphNodeRepository;

    @Autowired
    private DependencyRepository dependencyRepository;

    @Autowired
    private DependencyGraphViewRepository graphViewRepository;

    private UUID seedNode(UUID tenantId, String name) {
        UUID serviceId = UUID.randomUUID();
        graphNodeRepository.upsert(new GraphNodeUpsert(tenantId, serviceId, name, null, null, "HEALTHY"));
        return serviceId;
    }

    private void seedEdge(UUID tenantId, UUID source, UUID target, DependencyType type) {
        Instant now = Instant.now();
        dependencyRepository.save(
                new Dependency(UUID.randomUUID(), tenantId, source, target, type, DependencyProtocol.HTTP, null, now, now, null));
    }

    private HttpResponse<String> getCycles(UUID tenantId, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/cycles" + query))
                .header("X-Tenant-Id", tenantId.toString())
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertTraceIdHeaderMatchesBody(HttpResponse<String> resp) throws Exception {
        String headerTraceId = resp.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(TRACE_ID.matcher(headerTraceId).matches()).as("header trace id is 32 lowercase hex").isTrue();
        String bodyTraceId = objectMapper.readTree(resp.body()).get("traceId").stringValue();
        assertThat(bodyTraceId).isEqualTo(headerTraceId);
    }

    private static Set<String> memberSet(JsonNode cycle) {
        return cycle.get("members").valueStream().map(JsonNode::stringValue).collect(Collectors.toSet());
    }

    @Test
    void threeNodeCycleDedupesToExactlyOneEntryRegardlessOfStartingMember() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a");
        UUID b = seedNode(tenantId, "svc-b");
        UUID c = seedNode(tenantId, "svc-c");
        seedEdge(tenantId, a, b, DependencyType.DECLARED);
        seedEdge(tenantId, b, c, DependencyType.DECLARED);
        seedEdge(tenantId, c, a, DependencyType.DECLARED);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getCycles(tenantId, "");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("cycles")).hasSize(1);
        JsonNode cycle = data.get("cycles").get(0);
        assertThat(cycle.get("length").intValue()).isEqualTo(3);
        assertThat(memberSet(cycle)).containsExactlyInAnyOrder(a.toString(), b.toString(), c.toString());
        assertThat(data.get("truncated").booleanValue()).isFalse();
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void twoNodeMutualDependencyIsALengthTwoCycle() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a");
        UUID b = seedNode(tenantId, "svc-b");
        seedEdge(tenantId, a, b, DependencyType.DECLARED);
        seedEdge(tenantId, b, a, DependencyType.DECLARED);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getCycles(tenantId, "");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("cycles")).hasSize(1);
        assertThat(data.get("cycles").get(0).get("length").intValue()).isEqualTo(2);
    }

    @Test
    void nonCyclicGraphReturnsEmptyList() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a");
        UUID b = seedNode(tenantId, "svc-b");
        UUID c = seedNode(tenantId, "svc-c");
        seedEdge(tenantId, a, b, DependencyType.DECLARED);
        seedEdge(tenantId, b, c, DependencyType.DECLARED);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getCycles(tenantId, "");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("cycles")).isEmpty();
        assertThat(data.get("truncated").booleanValue()).isFalse();
    }

    @Test
    void typeFilterOnlyFindsCyclesOfThatType() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a");
        UUID b = seedNode(tenantId, "svc-b");
        seedEdge(tenantId, a, b, DependencyType.OBSERVED);
        seedEdge(tenantId, b, a, DependencyType.OBSERVED);
        graphViewRepository.refresh();

        HttpResponse<String> declaredOnly = getCycles(tenantId, "?type=DECLARED");
        HttpResponse<String> observedOnly = getCycles(tenantId, "?type=OBSERVED");

        assertThat(objectMapper.readTree(declaredOnly.body()).get("data").get("cycles")).isEmpty();
        assertThat(objectMapper.readTree(observedOnly.body()).get("data").get("cycles")).hasSize(1);
    }

    @Test
    void crossTenantCycleIsNeverReturned() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID a = seedNode(otherTenant, "svc-a");
        UUID b = seedNode(otherTenant, "svc-b");
        seedEdge(otherTenant, a, b, DependencyType.DECLARED);
        seedEdge(otherTenant, b, a, DependencyType.DECLARED);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getCycles(tenantId, "");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("cycles")).isEmpty();
    }

    @Test
    void cycleLongerThanMaxLengthIsNotFound() throws Exception {
        UUID tenantId = UUID.randomUUID();
        List<UUID> ids = IntStream.range(0, CycleService.MAX_CYCLE_LENGTH + 1)
                .mapToObj(i -> seedNode(tenantId, "svc-" + i))
                .toList();
        for (int i = 0; i < ids.size(); i++) {
            seedEdge(tenantId, ids.get(i), ids.get((i + 1) % ids.size()), DependencyType.DECLARED);
        }
        graphViewRepository.refresh();

        HttpResponse<String> resp = getCycles(tenantId, "");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("cycles")).isEmpty();
    }

    @Test
    void invalidTypeReturns400() throws Exception {
        HttpResponse<String> resp = getCycles(UUID.randomUUID(), "?type=NOT_A_TYPE");

        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("BAD_REQUEST");
    }
}
