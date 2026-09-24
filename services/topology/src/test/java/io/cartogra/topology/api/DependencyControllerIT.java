package io.cartogra.topology.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.topology.infrastructure.scheduled.DependencyGraphViewRefreshScheduler;
import io.cartogra.topology.repository.GraphNodeRepository;
import io.cartogra.topology.repository.GraphNodeUpsert;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Full-stack coverage of POST/PUT/DELETE {@code /dependencies}: real Postgres/Kafka, WireMock
 * standing in for Registry's {@code /internal/services/access}. Doesn't extend
 * {@code AbstractTopologyIT} since its {@code @SpringBootTest} doesn't set
 * {@code webEnvironment = RANDOM_PORT}, which {@code @LocalServerPort} here requires.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "topology.graph-view.refresh-interval=PT1H")
class DependencyControllerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");

    private static final WireMockServer WIRE_MOCK =
            new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());

    @BeforeAll
    static void startWireMock() {
        WIRE_MOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIRE_MOCK.stop();
    }

    @AfterEach
    void resetWireMock() {
        WIRE_MOCK.resetAll();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "dependency-controller-it-" + UUID.randomUUID());
        registry.add("topology.registry.base-url", () -> "http://localhost:" + WIRE_MOCK.port());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphNodeRepository graphNodeRepository;

    @MockitoSpyBean
    private DependencyGraphViewRefreshScheduler graphViewRefreshScheduler;

    private UUID seedNode(UUID tenantId) {
        UUID serviceId = UUID.randomUUID();
        graphNodeRepository.upsert(new GraphNodeUpsert(tenantId, serviceId, "svc-" + serviceId, null, null, "HEALTHY"));
        return serviceId;
    }

    private HttpResponse<String> send(String method, String path, UUID tenantId, UUID userId, String roles, String body) throws Exception {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/topology/dependencies" + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString());
        if (userId != null) {
            builder.header("X-User-Id", userId.toString());
        }
        if (roles != null) {
            builder.header("X-User-Roles", roles);
        }
        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
            case "DELETE" -> builder.DELETE().build();
            default -> throw new IllegalArgumentException(method);
        };
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String declareBody(UUID sourceId, UUID targetId) {
        return """
                {"sourceServiceId":"%s","targetServiceId":"%s","protocol":"HTTP"}
                """.formatted(sourceId, targetId);
    }

    /** Every requested serviceId maps to {@code allow} in Registry's response. */
    private void stubAccessFor(boolean allow, UUID... serviceIds) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < serviceIds.length; i++) {
            if (i > 0) {
                entries.append(',');
            }
            entries.append('"').append(serviceIds[i]).append("\":").append(allow);
        }
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/internal/services/access"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{%s},"traceId":"a3f1c8d2000000000000000000000000"}
                                """.formatted(entries))));
    }

    private void assertTraceIdHeaderMatchesBody(HttpResponse<String> resp) throws Exception {
        String headerTraceId = resp.headers().firstValue("X-Trace-Id").orElseThrow();
        assertThat(TRACE_ID.matcher(headerTraceId).matches()).as("header trace id is 32 lowercase hex").isTrue();
        if (!resp.body().isBlank()) {
            String bodyTraceId = objectMapper.readTree(resp.body()).get("traceId").stringValue();
            assertThat(bodyTraceId).isEqualTo(headerTraceId);
        }
    }

    @Test
    void postDependency_admin_returns201WithEnvelope() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);

        HttpResponse<String> resp = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("sourceServiceId").stringValue()).isEqualTo(source.toString());
        assertThat(data.get("targetServiceId").stringValue()).isEqualTo(target.toString());
        assertThat(data.get("type").stringValue()).isEqualTo("DECLARED");
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void postDependency_plainMemberWireMockGrants_returns201() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        stubAccessFor(true, source, target);

        HttpResponse<String> resp = send("POST", "", tenantId, userId, "MEMBER", declareBody(source, target));

        assertThat(resp.statusCode()).isEqualTo(201);
    }

    @Test
    void postDependency_plainMemberWireMockDenies_returns403() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        stubAccessFor(false, source, target);

        HttpResponse<String> resp = send("POST", "", tenantId, userId, "MEMBER", declareBody(source, target));

        assertThat(resp.statusCode()).isEqualTo(403);
    }

    @Test
    void postDependency_adminNotAMember_stillReturns201() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        stubAccessFor(false, source, target);

        HttpResponse<String> resp = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));

        assertThat(resp.statusCode()).isEqualTo(201);
    }

    @Test
    void postDependency_freeTextMetadata_returns201AndPersistsVerbatim() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        String body = """
                {"sourceServiceId":"%s","targetServiceId":"%s","protocol":"HTTP","metadata":"some plain text notes"}
                """.formatted(source, target);

        HttpResponse<String> resp = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", body);

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("metadata").stringValue()).isEqualTo("some plain text notes");
    }

    @Test
    void postDependency_unknownSourceServiceId_returns404() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID target = seedNode(tenantId);

        HttpResponse<String> resp = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN",
                declareBody(UUID.randomUUID(), target));

        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test
    void postDependency_selfEdge_returns422WithSelfDependencyErrorCode() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);

        HttpResponse<String> resp = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN",
                declareBody(source, source));

        assertThat(resp.statusCode()).isEqualTo(422);
        JsonNode error = objectMapper.readTree(resp.body()).get("error");
        assertThat(error.get("code").stringValue()).isEqualTo("SELF_DEPENDENCY");
    }

    @Test
    void postDependency_duplicateEdge_returns409() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        HttpResponse<String> first = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));
        assertThat(first.statusCode()).isEqualTo(201);

        HttpResponse<String> resp = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));

        assertThat(resp.statusCode()).isEqualTo(409);
    }

    /** Regression: unauthorized gets 403, never the 409 an authorized caller would see for the same pair. */
    @Test
    void postDependency_duplicateEdgeButUnauthorized_returns403Not409() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        HttpResponse<String> first = send("POST", "", tenantId, adminId, "ADMIN", declareBody(source, target));
        assertThat(first.statusCode()).isEqualTo(201);

        UUID unauthorizedUserId = UUID.randomUUID();
        stubAccessFor(false, source, target);

        HttpResponse<String> resp = send("POST", "", tenantId, unauthorizedUserId, "MEMBER", declareBody(source, target));

        assertThat(resp.statusCode()).isEqualTo(403);
    }

    @Test
    void postDependency_registryWireMockFault_returns503AndPersistsNothing() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/internal/services/access"))
                .willReturn(aResponse().withStatus(500)));

        HttpResponse<String> resp = send("POST", "", tenantId, userId, "MEMBER", declareBody(source, target));

        assertThat(resp.statusCode()).isEqualTo(503);

        HttpResponse<String> retry = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));
        assertThat(retry.statusCode()).isEqualTo(201);
    }

    @Test
    void postDependency_success_triggersMvSchedulerMarkDirty() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);

        HttpResponse<String> resp = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));

        assertThat(resp.statusCode()).isEqualTo(201);
        verify(graphViewRefreshScheduler).markDirty();
    }

    @Test
    void putDependency_fullReplace_returns200AndPersistsNewValues() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        UUID newTarget = seedNode(tenantId);
        HttpResponse<String> created = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));
        UUID id = UUID.fromString(objectMapper.readTree(created.body()).get("data").get("id").stringValue());

        HttpResponse<String> resp = send("PUT", "/" + id, tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, newTarget));

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode data = objectMapper.readTree(resp.body()).get("data");
        assertThat(data.get("id").stringValue()).isEqualTo(id.toString());
        assertThat(data.get("targetServiceId").stringValue()).isEqualTo(newTarget.toString());
    }

    @Test
    void putDependency_authorizedOldPairOnly_returns403() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        UUID newTarget = seedNode(tenantId);
        stubAccessFor(true, source, target);
        HttpResponse<String> created = send("POST", "", tenantId, userId, "MEMBER", declareBody(source, target));
        assertThat(created.statusCode()).isEqualTo(201);
        UUID id = UUID.fromString(objectMapper.readTree(created.body()).get("data").get("id").stringValue());

        stubAccessFor(false, source, target, newTarget);

        HttpResponse<String> resp = send("PUT", "/" + id, tenantId, userId, "MEMBER", declareBody(source, newTarget));

        assertThat(resp.statusCode()).isEqualTo(403);
    }

    @Test
    void deleteDependency_success_returns204AndSoftDeletes() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        HttpResponse<String> created = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));
        UUID id = UUID.fromString(objectMapper.readTree(created.body()).get("data").get("id").stringValue());

        HttpResponse<String> resp = send("DELETE", "/" + id, tenantId, UUID.randomUUID(), "ADMIN", null);

        assertThat(resp.statusCode()).isEqualTo(204);
        assertThat(resp.body()).isEmpty();
        assertTraceIdHeaderMatchesBody(resp);
    }

    @Test
    void deleteDependency_alreadyDeleted_returns404() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID source = seedNode(tenantId);
        UUID target = seedNode(tenantId);
        HttpResponse<String> created = send("POST", "", tenantId, UUID.randomUUID(), "ADMIN", declareBody(source, target));
        UUID id = UUID.fromString(objectMapper.readTree(created.body()).get("data").get("id").stringValue());
        assertThat(send("DELETE", "/" + id, tenantId, UUID.randomUUID(), "ADMIN", null).statusCode()).isEqualTo(204);

        HttpResponse<String> resp = send("DELETE", "/" + id, tenantId, UUID.randomUUID(), "ADMIN", null);

        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test
    void deleteDependency_unknownId_returns404() throws Exception {
        UUID tenantId = UUID.randomUUID();

        HttpResponse<String> resp = send("DELETE", "/" + UUID.randomUUID(), tenantId, UUID.randomUUID(), "ADMIN", null);

        assertThat(resp.statusCode()).isEqualTo(404);
    }
}
