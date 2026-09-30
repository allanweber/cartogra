package io.cartogra.topology.api;

import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.SpofService;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage of {@code GET /spofs}: real Postgres, real fan-in aggregation over
 * {@code dependency_graph_edges}. Mirrors {@link BlastRadiusControllerIT}'s setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "topology.graph-view.refresh-interval=PT1H")
class SpofControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> io.cartogra.test.PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", io.cartogra.test.PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", io.cartogra.test.PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", io.cartogra.test.KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "spof-controller-it-" + UUID.randomUUID());
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
    private NamedParameterJdbcTemplate jdbc;

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

    private void seedThreshold(UUID tenantId, int threshold) {
        jdbc.update("INSERT INTO tenant_topology_settings (tenant_id, spof_fan_in_threshold) VALUES (:tenantId, :threshold)",
                new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("threshold", threshold));
    }

    private void seedFanIn(UUID tenantId, UUID target, int count, @Nullable UUID teamId, @Nullable String tier) {
        graphNodeRepository.upsert(new GraphNodeUpsert(tenantId, target, "target", teamId, tier, "HEALTHY"));
        for (int i = 0; i < count; i++) {
            UUID dependent = seedNode(tenantId, "dependent-" + i, UUID.randomUUID(), "STANDARD");
            seedEdge(tenantId, dependent, target);
        }
    }

    private HttpResponse<String> getSpofs(UUID tenantId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/spofs"))
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

    @Test
    void fanInAtDefaultThresholdIsFlaggedWithWarningSeverity() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        seedFanIn(tenantId, target, SpofService.DEFAULT_THRESHOLD, UUID.randomUUID(), "STANDARD");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getSpofs(tenantId);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("threshold").intValue()).isEqualTo(SpofService.DEFAULT_THRESHOLD);
        assertThat(data.get("rationale").stringValue()).isNotBlank();
        assertThat(data.get("items")).hasSize(1);
        JsonNode item = data.get("items").get(0);
        assertThat(item.get("serviceId").stringValue()).isEqualTo(target.toString());
        assertThat(item.get("fanIn").intValue()).isEqualTo(SpofService.DEFAULT_THRESHOLD);
        assertThat(item.get("severity").stringValue()).isEqualTo("warning");
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void fanInBelowThresholdIsNotFlagged() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        seedFanIn(tenantId, target, SpofService.DEFAULT_THRESHOLD - 1, UUID.randomUUID(), "STANDARD");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getSpofs(tenantId);

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("items")).isEmpty();
    }

    @Test
    void criticalTierEscalatesToCriticalSeverity() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        seedFanIn(tenantId, target, SpofService.DEFAULT_THRESHOLD, UUID.randomUUID(), "CRITICAL");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getSpofs(tenantId);

        JsonNode item = objectMapper.readTree(resp.body()).get("data").get("items").get(0);
        assertThat(item.get("severity").stringValue()).isEqualTo("critical");
    }

    @Test
    void orphanEscalatesToCriticalSeverityEvenAtStandardTier() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        seedFanIn(tenantId, target, SpofService.DEFAULT_THRESHOLD, null, "STANDARD");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getSpofs(tenantId);

        JsonNode item = objectMapper.readTree(resp.body()).get("data").get("items").get(0);
        assertThat(item.get("severity").stringValue()).isEqualTo("critical");
    }

    @Test
    void tenantThresholdOverrideChangesFlagging() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        seedThreshold(tenantId, 3);
        seedFanIn(tenantId, target, 3, UUID.randomUUID(), "STANDARD");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getSpofs(tenantId);

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("threshold").intValue()).isEqualTo(3);
        assertThat(data.get("items")).hasSize(1);
    }

    @Test
    void crossTenantSpofIsNeverReturned() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        seedFanIn(otherTenant, target, SpofService.DEFAULT_THRESHOLD, UUID.randomUUID(), "STANDARD");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getSpofs(tenantId);

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("items")).isEmpty();
    }
}
