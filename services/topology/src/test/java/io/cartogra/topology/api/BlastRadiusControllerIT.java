package io.cartogra.topology.api;

import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
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
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage of {@code GET /blast-radius/{serviceId}}: real Postgres, real recursive
 * CTE over {@code dependency_graph_edges}. Mirrors {@link GraphControllerIT}'s setup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "topology.graph-view.refresh-interval=PT1H")
class BlastRadiusControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> io.cartogra.test.PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", io.cartogra.test.PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", io.cartogra.test.PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", io.cartogra.test.KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "blast-radius-controller-it-" + UUID.randomUUID());
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

    private void seedEdge(UUID tenantId, UUID source, UUID target) {
        Instant now = Instant.now();
        dependencyRepository.save(new Dependency(UUID.randomUUID(), tenantId, source, target,
                DependencyType.DECLARED, DependencyProtocol.HTTP, null, now, now, null));
    }

    private HttpResponse<String> getBlastRadius(UUID tenantId, UUID serviceId, String query) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/blast-radius/" + serviceId + query))
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
    void returnsEnvelopeWithBothDirectionsAndTraceId() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a");
        UUID b = seedNode(tenantId, "svc-b");
        UUID c = seedNode(tenantId, "svc-c");
        seedEdge(tenantId, a, b);
        seedEdge(tenantId, c, a);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getBlastRadius(tenantId, a, "");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("serviceId").stringValue()).isEqualTo(a.toString());
        assertThat(data.get("upstream").get("entries")).hasSize(1);
        assertThat(data.get("upstream").get("entries").get(0).get("serviceId").stringValue()).isEqualTo(b.toString());
        assertThat(data.get("downstream").get("entries")).hasSize(1);
        assertThat(data.get("downstream").get("entries").get(0).get("serviceId").stringValue()).isEqualTo(c.toString());
        assertThat(data.get("maxDepth").intValue()).isEqualTo(3);
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void unknownServiceReturns404() throws Exception {
        HttpResponse<String> resp = getBlastRadius(UUID.randomUUID(), UUID.randomUUID(), "");

        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    void depthZeroReturns400() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getBlastRadius(tenantId, a, "?depth=0");

        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void depthAboveMaxIsClampedNotRejected() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getBlastRadius(tenantId, a, "?depth=999");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("maxDepth").intValue()).isEqualTo(6);
    }

    @Test
    void directionFilterLeavesTheOtherDirectionEmpty() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID a = seedNode(tenantId, "svc-a");
        UUID b = seedNode(tenantId, "svc-b");
        seedEdge(tenantId, a, b);
        graphViewRepository.refresh();

        HttpResponse<String> resp = getBlastRadius(tenantId, a, "?direction=UPSTREAM");

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("upstream").get("entries")).hasSize(1);
        assertThat(data.get("downstream").get("entries")).isEmpty();
    }

    @Test
    void crossTenantServiceIsNeverFound() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        UUID a = seedNode(otherTenant, "svc-a");
        graphViewRepository.refresh();

        HttpResponse<String> resp = getBlastRadius(tenantId, a, "");

        assertThat(resp.statusCode()).isEqualTo(404);
    }
}
