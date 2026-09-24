package io.cartogra.topology.api;

import io.cartogra.topology.AbstractTopologyIT;
import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.repository.DependencyGraphViewRepository;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage of {@code GET /graph}: real Postgres, real join against
 * {@code dependency_graph_edges}. Extends {@link AbstractTopologyIT} but overrides the web
 * environment to a random port so it can drive the endpoint over real HTTP, mirroring
 * {@code DependencyControllerIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "topology.graph-view.refresh-interval=PT1H")
class GraphControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> io.cartogra.test.PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", io.cartogra.test.PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", io.cartogra.test.PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", io.cartogra.test.KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "graph-controller-it-" + UUID.randomUUID());
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

    private UUID seedNode(UUID tenantId, String name, UUID teamId) {
        UUID serviceId = UUID.randomUUID();
        graphNodeRepository.upsert(new GraphNodeUpsert(tenantId, serviceId, name, teamId, null, "HEALTHY"));
        return serviceId;
    }

    private void seedEdge(UUID tenantId, UUID source, UUID target, DependencyType type, DependencyProtocol protocol) {
        seedEdge(tenantId, source, target, type, protocol, null);
    }

    private void seedEdge(UUID tenantId, UUID source, UUID target, DependencyType type, DependencyProtocol protocol,
            String metadata) {
        Instant now = Instant.now();
        dependencyRepository.save(
                new Dependency(UUID.randomUUID(), tenantId, source, target, type, protocol, metadata, now, now, null));
    }

    private HttpResponse<String> getGraph(UUID tenantId, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/graph" + query))
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
    void returnsEnvelopeWithNodesEdgesAndTraceId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a", null);
        UUID b = seedNode(tenantId, "svc-b", null);
        seedEdge(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("nodes")).hasSize(2);
        assertThat(data.get("edges")).hasSize(1);
        assertThat(data.get("truncated").booleanValue()).isFalse();
        JsonNode edge = data.get("edges").get(0);
        assertThat(edge.get("source").stringValue()).isEqualTo(a.toString());
        assertThat(edge.get("target").stringValue()).isEqualTo(b.toString());
        assertThat(edge.get("dependencyType").stringValue()).isEqualTo("DECLARED");
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void edgeIncludesProtocolAndMetadata() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a", null);
        UUID b = seedNode(tenantId, "svc-b", null);
        seedEdge(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.GRPC, "internal RPC only");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "");

        JsonNode edge = objectMapper.readTree(resp.body()).get("data").get("edges").get(0);
        assertThat(edge.get("protocol").stringValue()).isEqualTo("GRPC");
        assertThat(edge.get("metadata").stringValue()).isEqualTo("internal RPC only");
    }

    @Test
    void nodeResponseIsSlimAndKeyedByServiceId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a", team);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "");

        JsonNode node = objectMapper.readTree(resp.body()).get("data").get("nodes").get(0);
        assertThat(node.get("serviceId").stringValue()).isEqualTo(a.toString());
        assertThat(node.get("name").stringValue()).isEqualTo("svc-a");
        assertThat(node.get("teamId").stringValue()).isEqualTo(team.toString());
        assertThat(node.get("healthStatus").stringValue()).isEqualTo("HEALTHY");
        assertThat(node.propertyNames()).containsExactlyInAnyOrder("serviceId", "name", "teamId", "tier", "healthStatus");
    }

    @Test
    void teamIdFilterIncludesEitherEndpointAndCrossTeamNeighbor() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID teamX = UUID.randomUUID();
        UUID teamY = UUID.randomUUID();
        UUID teamMember = seedNode(tenantId, "team-x-svc", teamX);
        UUID otherTeamNeighbor = seedNode(tenantId, "team-y-svc", teamY);
        UUID unrelated = seedNode(tenantId, "unrelated-svc", null);
        seedEdge(tenantId, teamMember, otherTeamNeighbor, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "?teamId=" + teamX);

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        List<String> nodeIds = data.get("nodes").valueStream().map(n -> n.get("serviceId").stringValue()).toList();
        assertThat(nodeIds).containsExactlyInAnyOrder(teamMember.toString(), otherTeamNeighbor.toString());
        assertThat(nodeIds).doesNotContain(unrelated.toString());
        assertThat(data.get("edges")).hasSize(1);
    }

    @Test
    void typeFilterScopesEdgesButNotTeamNodes() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a", team);
        UUID b = seedNode(tenantId, "svc-b", team);
        seedEdge(tenantId, a, b, DependencyType.OBSERVED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "?teamId=" + team + "&type=DECLARED");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("nodes")).hasSize(2);
        assertThat(data.get("edges")).isEmpty();
    }

    @Test
    void invalidTypeReturns400() throws Exception {
        HttpResponse<String> resp = getGraph(UUID.randomUUID(), "?type=NOT_A_TYPE");

        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void limitIsClampedToTheHardCapAndTruncatedIsSetWhenMoreExist() throws Exception {
        UUID tenantId = UUID.randomUUID();
        IntStream.range(0, 3).forEach(i -> seedNode(tenantId, "svc-" + i, null));
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "?limit=2");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("nodes")).hasSize(2);
        assertThat(data.get("truncated").booleanValue()).isTrue();
        List<String> names = data.get("nodes").valueStream().map(n -> n.get("name").stringValue()).toList();
        assertThat(names).containsExactly("svc-0", "svc-1");
    }

    @Test
    void invalidLimitBelowOneReturns400() throws Exception {
        HttpResponse<String> resp = getGraph(UUID.randomUUID(), "?limit=0");

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test
    void crossTenantNodeIsNeverReturned() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        seedNode(tenantId, "mine", null);
        seedNode(otherTenant, "not-mine", null);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        List<String> names = data.get("nodes").valueStream().map(n -> n.get("name").stringValue()).toList();
        assertThat(names).containsExactly("mine");
    }

    @Test
    void crossTenantEdgeIsNeverReturnedDespiteSharedServiceIdCollisionRisk() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a", null);
        UUID b = seedNode(tenantId, "svc-b", null);
        UUID otherA = seedNode(otherTenant, "other-a", null);
        UUID otherB = seedNode(otherTenant, "other-b", null);
        seedEdge(tenantId, a, b, DependencyType.DECLARED, DependencyProtocol.HTTP);
        seedEdge(otherTenant, otherA, otherB, DependencyType.DECLARED, DependencyProtocol.HTTP);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("edges")).hasSize(1);
    }

    @Test
    void noEdgesReferenceANodeAbsentFromTheResponse() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID team = UUID.randomUUID();
        seedNode(tenantId, "only-node", team);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getGraph(tenantId, "?teamId=" + team);

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("nodes")).hasSize(1);
        assertThat(data.get("edges")).isEmpty();
    }
}
