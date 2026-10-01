package io.cartogra.topology.api;

import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyDrift;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.DriftType;
import io.cartogra.topology.domain.SpofService;
import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.jspecify.annotations.Nullable;
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
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage of {@code GET /risks}: real Postgres, aggregating cycle, SPOF, orphan, and
 * drift findings. Mirrors {@link BlastRadiusControllerIT}'s setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "topology.graph-view.refresh-interval=PT1H")
class RiskControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> io.cartogra.test.PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", io.cartogra.test.PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", io.cartogra.test.PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", io.cartogra.test.KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "risk-controller-it-" + UUID.randomUUID());
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

    @Autowired
    private DependencyDriftRepository driftRepository;

    private UUID seedNode(UUID tenantId, String name, @Nullable UUID teamId, @Nullable String tier) {
        UUID serviceId = UUID.randomUUID();
        graphNodeRepository.upsert(new GraphNodeUpsert(tenantId, serviceId, name, teamId, tier, "HEALTHY"));
        return serviceId;
    }

    private void seedEdge(UUID tenantId, UUID source, UUID target) {
        Instant now = Instant.now();
        dependencyRepository.save(new Dependency(UUID.randomUUID(), tenantId, source, target,
                DependencyType.DECLARED, DependencyProtocol.HTTP, null, now, now, null));
    }

    private void seedCycle(UUID tenantId) {
        UUID a = seedNode(tenantId, "cycle-a", UUID.randomUUID(), "STANDARD");
        UUID b = seedNode(tenantId, "cycle-b", UUID.randomUUID(), "STANDARD");
        seedEdge(tenantId, a, b);
        seedEdge(tenantId, b, a);
    }

    private void seedSpof(UUID tenantId) {
        UUID target = seedNode(tenantId, "spof-target", UUID.randomUUID(), "STANDARD");
        for (int i = 0; i < SpofService.DEFAULT_THRESHOLD; i++) {
            UUID dependent = seedNode(tenantId, "spof-dependent-" + i, UUID.randomUUID(), "STANDARD");
            seedEdge(tenantId, dependent, target);
        }
    }

    private void seedOrphan(UUID tenantId) {
        seedNode(tenantId, "orphan-svc", null, "STANDARD");
    }

    private void seedDrift(UUID tenantId) {
        UUID source = seedNode(tenantId, "drift-source", UUID.randomUUID(), "STANDARD");
        UUID target = seedNode(tenantId, "drift-target", UUID.randomUUID(), "STANDARD");
        Instant now = Instant.now();
        driftRepository.save(new DependencyDrift(UUID.randomUUID(), tenantId, source, target,
                DriftType.UNDECLARED, now, null, null));
    }

    private HttpResponse<String> getRisks(UUID tenantId, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/risks" + query))
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

    private static java.util.Set<String> types(JsonNode items) {
        return StreamSupport.stream(items.valueStream().spliterator(), false)
                .map(item -> item.get("type").stringValue())
                .collect(Collectors.toSet());
    }

    @Test
    void aggregatesAllFourRiskTypes() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedCycle(tenantId);
        seedSpof(tenantId);
        seedOrphan(tenantId);
        seedDrift(tenantId);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getRisks(tenantId, "");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("total").longValue()).isEqualTo(4);
        assertThat(data.get("truncated").booleanValue()).isFalse();
        assertThat(types(data.get("items"))).containsExactlyInAnyOrder("cycle", "spof", "orphan", "drift");
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void paginationRespectsLimitAndOffset() throws Exception {
        UUID tenantId = UUID.randomUUID();
        seedCycle(tenantId);
        seedSpof(tenantId);
        seedOrphan(tenantId);
        seedDrift(tenantId);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getRisks(tenantId, "?limit=2&offset=2");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("total").longValue()).isEqualTo(4);
        assertThat(data.get("items")).hasSize(2);
        assertThat(data.get("limit").intValue()).isEqualTo(2);
        assertThat(data.get("offset").intValue()).isEqualTo(2);
    }

    @Test
    void emptyGraphReturnsEmptyPage() throws Exception {
        UUID tenantId = UUID.randomUUID();
        graphViewRepository.refresh();

        HttpResponse<String> resp = getRisks(tenantId, "");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("items")).isEmpty();
        assertThat(data.get("total").longValue()).isZero();
    }

    @Test
    void crossTenantRisksAreNeverReturned() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        seedOrphan(otherTenant);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getRisks(tenantId, "");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("items")).isEmpty();
    }
}
