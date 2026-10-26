package io.cartogra.topology.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.topology.domain.Dependency;
import io.cartogra.topology.domain.DependencyProtocol;
import io.cartogra.topology.domain.DependencyType;
import io.cartogra.topology.domain.DriftDetectionService;
import io.cartogra.topology.domain.DriftType;
import io.cartogra.topology.domain.ObservedDependencyService;
import io.cartogra.topology.domain.SpanKind;
import io.cartogra.topology.domain.SpanObservation;
import io.cartogra.topology.repository.DependencyDriftRepository;
import io.cartogra.topology.repository.DependencyRepository;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "topology.graph-view.refresh-interval=PT1H",
        "topology.drift.sweep-interval=PT1H",
        "topology.drift.missing-after=PT1M"})
class DriftControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        WIRE_MOCK.start();
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/internal/services/access"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{},\"traceId\":\"a3f1c8d2000000000000000000000000\"}")));
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "drift-controller-it-" + UUID.randomUUID());
        registry.add("topology.registry.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
    }

    @LocalServerPort
    private int port;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private GraphNodeRepository graphNodeRepository;
    @Autowired private DependencyRepository dependencyRepository;
    @Autowired private DependencyDriftRepository driftRepository;
    @Autowired private DriftDetectionService detectionService;
    @Autowired private ObservedDependencyService observedDependencyService;

    private UUID node(UUID tenantId, String name) {
        UUID id = UUID.randomUUID();
        graphNodeRepository.upsert(new GraphNodeUpsert(tenantId, id, name + "-" + id, null, null, "HEALTHY"));
        return id;
    }

    private void edge(UUID tenantId, UUID source, UUID target, DependencyType type, Instant createdAt) {
        dependencyRepository.save(new Dependency(UUID.randomUUID(), tenantId, source, target, type,
                DependencyProtocol.HTTP, null, createdAt, createdAt, null));
    }

    private HttpResponse<String> send(String method, String path, UUID tenantId, String roles, String body)
            throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/drifts" + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .header("X-User-Id", UUID.randomUUID().toString());
        if (roles != null) {
            builder.header("X-User-Roles", roles);
        }
        HttpRequest request = "POST".equals(method)
                ? builder.POST(HttpRequest.BodyPublishers.ofString(body)).build()
                : builder.GET().build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private UUID seedUndeclared(UUID tenant, UUID source, UUID target) {
        edge(tenant, source, target, DependencyType.OBSERVED, Instant.now());
        detectionService.sweep(tenant, Instant.now());
        return driftRepository.findActiveByEdge(tenant, source, target, DriftType.UNDECLARED).orElseThrow().id();
    }

    @Test
    void listReturnsActiveDriftsInTheEnvelope() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID a = node(tenant, "a");
        UUID b = node(tenant, "b");
        seedUndeclared(tenant, a, b);

        HttpResponse<String> resp = send("GET", "", tenant, null, null);

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("total").longValue()).isEqualTo(1);
        JsonNode item = data.get("items").get(0);
        assertThat(item.get("type").stringValue()).isEqualTo("UNDECLARED");
        assertThat(item.get("sourceServiceId").stringValue()).isEqualTo(a.toString());
        assertThat(objectMapper.readTree(resp.body()).get("traceId").stringValue()).hasSize(32);
    }

    @Test
    void clientSpanBecomesUndeclaredDriftThatAcceptingMakesDeclared() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID reporting = UUID.randomUUID();
        UUID payments = UUID.randomUUID();
        graphNodeRepository.upsert(new GraphNodeUpsert(tenant, reporting, "reporting-service", null, null, "HEALTHY"));
        graphNodeRepository.upsert(new GraphNodeUpsert(tenant, payments, "payments-service", null, null, "HEALTHY"));

        observedDependencyService.ingest(tenant, List.of(new SpanObservation("reporting-service", SpanKind.CLIENT,
                "trace-1", "span-1", null, "payments-service", null, DependencyProtocol.HTTP, null, null)));
        detectionService.sweep(tenant, Instant.now());

        UUID driftId = driftRepository.findActiveByEdge(tenant, reporting, payments, DriftType.UNDECLARED)
                .orElseThrow().id();
        assertThat(send("POST", "/" + driftId + "/resolve", tenant, "ADMIN",
                "{\"action\":\"ACCEPT_OBSERVED\"}").statusCode()).isEqualTo(200);
        assertThat(dependencyRepository.findLivePairs(tenant, DependencyType.DECLARED))
                .extracting(p -> p.sourceServiceId()).containsExactly(reporting);
    }

    @Test
    void listIsTenantScoped() throws Exception {
        UUID tenant = UUID.randomUUID();
        seedUndeclared(tenant, node(tenant, "a"), node(tenant, "b"));

        HttpResponse<String> resp = send("GET", "", UUID.randomUUID(), null, null);

        assertThat(objectMapper.readTree(resp.body()).get("data").get("items")).isEmpty();
    }

    @Test
    void acceptObservedDeclaresTheEdgeAndClosesTheDrift() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID a = node(tenant, "a");
        UUID b = node(tenant, "b");
        UUID driftId = seedUndeclared(tenant, a, b);

        HttpResponse<String> resp = send("POST", "/" + driftId + "/resolve", tenant, "ADMIN",
                "{\"action\":\"ACCEPT_OBSERVED\"}");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("resolution").stringValue()).isEqualTo("ACCEPTED");
        assertThat(dependencyRepository.findByEdgeIdentity(tenant, a, b, DependencyType.DECLARED,
                DependencyProtocol.HTTP)).isPresent();
        detectionService.sweep(tenant, Instant.now());
        assertThat(driftRepository.findAllActive(tenant)).isEmpty();
    }

    @Test
    void removeDeclaredSoftDeletesTheEdgeForMissingDrift() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID a = node(tenant, "a");
        UUID b = node(tenant, "b");
        UUID c = node(tenant, "c");
        Instant old = Instant.now().minusSeconds(3600);
        edge(tenant, a, b, DependencyType.DECLARED, old);
        edge(tenant, a, c, DependencyType.DECLARED, old);
        edge(tenant, a, c, DependencyType.OBSERVED, Instant.now());
        detectionService.sweep(tenant, Instant.now());
        UUID driftId = driftRepository.findActiveByEdge(tenant, a, b, DriftType.MISSING).orElseThrow().id();

        HttpResponse<String> resp = send("POST", "/" + driftId + "/resolve", tenant, "ADMIN",
                "{\"action\":\"REMOVE_DECLARED\"}");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(dependencyRepository.findLivePairs(tenant, DependencyType.DECLARED))
                .extracting(p -> p.targetServiceId()).containsExactly(c);
    }

    @Test
    void dismissedDriftIsNotRaisedAgainBySubsequentSweeps() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID driftId = seedUndeclared(tenant, node(tenant, "a"), node(tenant, "b"));

        HttpResponse<String> resp = send("POST", "/" + driftId + "/resolve", tenant, "ADMIN",
                "{\"action\":\"DISMISS\"}");
        detectionService.sweep(tenant, Instant.now());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(driftRepository.findAllActive(tenant)).isEmpty();
    }

    @Test
    void wrongActionIs422AndLeavesDriftActive() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID driftId = seedUndeclared(tenant, node(tenant, "a"), node(tenant, "b"));

        HttpResponse<String> resp = send("POST", "/" + driftId + "/resolve", tenant, "ADMIN",
                "{\"action\":\"REMOVE_DECLARED\"}");

        assertThat(resp.statusCode()).isEqualTo(422);
        assertThat(objectMapper.readTree(resp.body()).get("error").get("code").stringValue())
                .isEqualTo("INVALID_DRIFT_ACTION");
        assertThat(driftRepository.findAllActive(tenant)).hasSize(1);
    }

    @Test
    void resolvingTwiceIs409() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID driftId = seedUndeclared(tenant, node(tenant, "a"), node(tenant, "b"));
        send("POST", "/" + driftId + "/resolve", tenant, "ADMIN", "{\"action\":\"DISMISS\"}");

        HttpResponse<String> resp = send("POST", "/" + driftId + "/resolve", tenant, "ADMIN",
                "{\"action\":\"DISMISS\"}");

        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(resp.body()).get("error").get("code").stringValue())
                .isEqualTo("DRIFT_ALREADY_RESOLVED");
    }

    @Test
    void unknownAndCrossTenantDriftAre404() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID driftId = seedUndeclared(tenant, node(tenant, "a"), node(tenant, "b"));

        assertThat(send("POST", "/" + UUID.randomUUID() + "/resolve", tenant, "ADMIN",
                "{\"action\":\"DISMISS\"}").statusCode()).isEqualTo(404);
        assertThat(send("POST", "/" + driftId + "/resolve", UUID.randomUUID(), "ADMIN",
                "{\"action\":\"DISMISS\"}").statusCode()).isEqualTo(404);
    }

    @Test
    void nonAdminWithoutTeamMembershipIs403() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID driftId = seedUndeclared(tenant, node(tenant, "a"), node(tenant, "b"));

        HttpResponse<String> resp = send("POST", "/" + driftId + "/resolve", tenant, "MEMBER",
                "{\"action\":\"DISMISS\"}");

        assertThat(resp.statusCode()).isEqualTo(403);
        assertThat(driftRepository.findAllActive(tenant)).hasSize(1);
    }

    @Test
    void invalidActionBodyIs400() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID driftId = seedUndeclared(tenant, node(tenant, "a"), node(tenant, "b"));

        assertThat(send("POST", "/" + driftId + "/resolve", tenant, "ADMIN", "{\"action\":\"NOPE\"}")
                .statusCode()).isEqualTo(400);
        assertThat(send("POST", "/" + driftId + "/resolve", tenant, "ADMIN", "{}").statusCode()).isEqualTo(400);
    }
}
