package io.cartogra.topology.api;

import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
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
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage of {@code GET /services/{serviceId}/dependencies}: real Postgres, no
 * dependency on the {@code dependency_graph_edges} materialized view (reads straight off the
 * {@code dependencies} table via {@code DependencyRepository.findByService}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "topology.graph-view.refresh-interval=PT1H")
class ServiceDependenciesControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    @org.springframework.test.context.DynamicPropertySource
    static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> io.cartogra.test.PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", io.cartogra.test.PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", io.cartogra.test.PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", io.cartogra.test.KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "service-dependencies-controller-it-" + UUID.randomUUID());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphNodeRepository graphNodeRepository;

    @Autowired
    private DependencyRepository dependencyRepository;

    private UUID seedNode(UUID tenantId, String name, String healthStatus) {
        UUID serviceId = UUID.randomUUID();
        graphNodeRepository.upsert(new GraphNodeUpsert(tenantId, serviceId, name, null, null, healthStatus));
        return serviceId;
    }

    private void seedEdge(UUID tenantId, UUID source, UUID target, DependencyType type, DependencyProtocol protocol) {
        Instant now = Instant.now();
        dependencyRepository.save(new Dependency(UUID.randomUUID(), tenantId, source, target, type, protocol, null, now, now, null));
    }

    private HttpResponse<String> getDependencies(UUID tenantId, UUID serviceId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/services/" + serviceId + "/dependencies"))
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
    void bucketsEdgesByDirectionRelativeToTheRequestedService() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID self = seedNode(tenantId, "checkout", "HEALTHY");
        UUID dependsOn = seedNode(tenantId, "payments", "DEGRADED");
        UUID dependsOnMe = seedNode(tenantId, "storefront", "HEALTHY");
        seedEdge(tenantId, self, dependsOn, DependencyType.DECLARED, DependencyProtocol.HTTP);
        seedEdge(tenantId, dependsOnMe, self, DependencyType.DECLARED, DependencyProtocol.GRPC);

        HttpResponse<String> resp = getDependencies(tenantId, self);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("downstream")).hasSize(1);
        assertThat(data.get("upstream")).hasSize(1);

        JsonNode downstreamEntry = data.get("downstream").get(0);
        assertThat(downstreamEntry.get("serviceId").stringValue()).isEqualTo(dependsOn.toString());
        assertThat(downstreamEntry.get("name").stringValue()).isEqualTo("payments");
        assertThat(downstreamEntry.get("healthStatus").stringValue()).isEqualTo("DEGRADED");
        assertThat(downstreamEntry.get("protocol").stringValue()).isEqualTo("HTTP");

        JsonNode upstreamEntry = data.get("upstream").get(0);
        assertThat(upstreamEntry.get("serviceId").stringValue()).isEqualTo(dependsOnMe.toString());
        assertThat(upstreamEntry.get("name").stringValue()).isEqualTo("storefront");
        assertThat(upstreamEntry.get("protocol").stringValue()).isEqualTo("GRPC");

        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void observedEdgesAreExcluded() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID self = seedNode(tenantId, "checkout", "HEALTHY");
        UUID other = seedNode(tenantId, "payments", "HEALTHY");
        seedEdge(tenantId, self, other, DependencyType.OBSERVED, DependencyProtocol.HTTP);

        HttpResponse<String> resp = getDependencies(tenantId, self);

        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("downstream")).isEmpty();
        assertThat(data.get("upstream")).isEmpty();
    }

    @Test
    void unknownServiceReturns404() throws Exception {
        HttpResponse<String> resp = getDependencies(UUID.randomUUID(), UUID.randomUUID());

        assertThat(resp.statusCode()).isEqualTo(404);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("NOT_FOUND");
    }

    @Test
    void crossTenantServiceIsTreatedAsUnknown() throws Exception {
        UUID otherTenant = UUID.randomUUID();
        UUID otherTenantsService = seedNode(otherTenant, "not-mine", "HEALTHY");

        HttpResponse<String> resp = getDependencies(UUID.randomUUID(), otherTenantsService);

        assertThat(resp.statusCode()).isEqualTo(404);
    }
}
