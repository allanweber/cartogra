package io.cartogra.contract.api;

import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.ContractHttp;
import io.cartogra.contract.repository.InboundEdgeRepository;
import io.cartogra.contract.repository.ContractCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** CI check: S-13 to S-18 of plan 034. */
class ContractCheckIT extends AbstractContractIT {

    static final String V1 = """
            openapi: 3.0.3
            info: {title: Billing, version: 1.0.0}
            paths:
              /entries:
                get:
                  responses:
                    '200': {description: ok}
            """;

    static final String BASELINE = """
            openapi: 3.0.3
            info: {title: Billing, version: 1.1.0}
            paths:
              /entries:
                get:
                  responses:
                    '200': {description: ok}
              /items:
                get:
                  responses:
                    '200': {description: ok}
            """;

    static final String BREAKING = """
            openapi: 3.0.3
            info: {title: Billing, version: 2.0.0}
            paths:
              /entries:
                get:
                  parameters:
                    - name: q
                      in: query
                      required: true
                      schema: {type: string}
                  responses:
                    '200': {description: ok}
            """;

    static final String COMPATIBLE = BASELINE.replace("version: 1.1.0", "version: 1.2.0") + """
              /extra:
                get:
                  responses:
                    '200': {description: ok}
            """;

    @LocalServerPort
    int port;

    @Autowired ObjectMapper objectMapper;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ContractCheckRepository checks;
    @MockitoSpyBean InboundEdgeRepository edges;

    ContractHttp http;
    UUID tenant;
    UUID apiKey;
    UUID service;

    @BeforeEach
    void setUp() {
        http = new ContractHttp(port);
        tenant = UUID.randomUUID();
        apiKey = UUID.randomUUID();
        service = UUID.randomUUID();
        liveService(tenant, service, "Billing");
        String id = data(http.create(tenant, service, V1)).get("id").stringValue();
        assertThat(http.publish(tenant, id, BASELINE).statusCode()).isEqualTo(201);
    }

    @Test
    void breakingByName() {
        List<UUID> consumers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID c = UUID.randomUUID();
            liveService(tenant, c, "caller-" + i);
            edge(tenant, c, service);
            consumers.add(c);
        }
        clearInvocations(edges);

        HttpResponse<String> resp = http.check(tenant, apiKey, ContractHttp.checkBody("bIlLiNg", BREAKING));

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = data(resp);
        assertThat(body.get("isBreaking").booleanValue()).isTrue();
        assertThat(body.get("baselineVersion").asInt()).isEqualTo(2);
        List<String> rules = new ArrayList<>();
        body.get("changes").forEach(c -> rules.add(c.get("rule").stringValue()));
        assertThat(rules).contains("path-removed", "parameter-required-added");
        List<String> listed = new ArrayList<>();
        body.get("affectedConsumers").forEach(c -> listed.add(c.get("serviceId").stringValue()));
        assertThat(listed).containsExactlyInAnyOrderElementsOf(consumers.stream().map(UUID::toString).toList());
        assertThat(objectMapper.readTree(resp.body()).get("traceId").stringValue()).isNotBlank();

        assertThat(rows(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM contract_checks
                WHERE tenant_id = :t AND is_breaking AND api_key_id = :k AND service_id = :s
                  AND cardinality(affected_consumer_ids) = 12
                """, params().addValue("k", apiKey).addValue("s", service), Integer.class)).isEqualTo(1);
        assertThat(versions(tenant)).isEqualTo(2);
        // The consumer load is one edge query for all 12 consumers, not one per consumer.
        verify(edges, times(1)).findConsumers(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void compatibleById() {
        UUID consumer = UUID.randomUUID();
        liveService(tenant, consumer, "caller");
        edge(tenant, consumer, service);

        HttpResponse<String> resp = http.check(tenant, apiKey, ContractHttp.checkBody(service.toString(), COMPATIBLE));

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode body = data(resp);
        assertThat(body.get("isBreaking").booleanValue()).isFalse();
        body.get("changes").forEach(c -> assertThat(c.get("breaking").booleanValue()).isFalse());
        assertThat(body.get("affectedConsumers").size()).isZero();
        assertThat(rows(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_breaking FROM contract_checks WHERE tenant_id = :t", params(),
                Boolean.class)).isFalse();
    }

    @Test
    void trailingSlashGivesSameResult() {
        String body = ContractHttp.checkBody("Billing", BREAKING);

        HttpResponse<String> plain = http.check("/check", tenant, apiKey, body);
        HttpResponse<String> slash = http.check("/check/", tenant, apiKey, body);

        assertThat(plain.statusCode()).as(plain.body()).isEqualTo(200);
        assertThat(slash.statusCode()).as(slash.body()).isEqualTo(200);
        assertThat(data(slash)).isEqualTo(data(plain));
        assertThat(http.post("/check/", tenant, "ADMIN", body).statusCode()).isEqualTo(403);
    }

    @Test
    void neverCreatesVersion() {
        assertThat(http.check(tenant, apiKey, ContractHttp.checkBody("billing", BREAKING)).statusCode()).isEqualTo(200);
        assertThat(http.check(tenant, apiKey, ContractHttp.checkBody("billing", COMPATIBLE)).statusCode()).isEqualTo(200);

        assertThat(versions(tenant)).isEqualTo(2);
        assertThat(rows(tenant)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contract_versions WHERE tenant_id = :t AND spec_hash IN "
                + "(SELECT candidate_hash FROM contract_checks WHERE tenant_id = :t)", params(), Integer.class))
                .isZero();
    }

    @Test
    void notFoundUnknownName() {
        assertNotFound(ContractHttp.checkBody("no-such-service", BREAKING));
    }

    @Test
    void notFoundServiceOfAnotherTenant() {
        UUID otherTenant = UUID.randomUUID();
        UUID otherService = UUID.randomUUID();
        liveService(otherTenant, otherService, "Other");
        assertThat(http.create(otherTenant, otherService, V1).statusCode()).isEqualTo(201);

        assertNotFound(ContractHttp.checkBody(otherService.toString(), BREAKING));
        assertNotFound(ContractHttp.checkBody("Other", BREAKING));
        assertThat(rows(otherTenant)).isZero();
    }

    @Test
    void notFoundDeletedService() {
        jdbc.update("UPDATE services SET deleted_at = now() WHERE tenant_id = :t AND service_id = :s",
                params().addValue("s", service));

        assertNotFound(ContractHttp.checkBody(service.toString(), BREAKING));
        assertNotFound(ContractHttp.checkBody("billing", BREAKING));
    }

    @Test
    void ambiguousName() {
        liveService(tenant, UUID.randomUUID(), "billing");

        HttpResponse<String> resp = http.check(tenant, apiKey, ContractHttp.checkBody("BILLING", BREAKING));

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(409);
        assertThat(error(resp)).isEqualTo("SERVICE_AMBIGUOUS");
        assertThat(rows(tenant)).isZero();
    }

    @Test
    void noBaseline() {
        UUID bare = UUID.randomUUID();
        liveService(tenant, bare, "bare");

        HttpResponse<String> resp = http.check(tenant, apiKey, ContractHttp.checkBody("bare", BREAKING));

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(404);
        assertThat(error(resp)).isEqualTo("BASELINE_NOT_FOUND");
        assertThat(rows(tenant)).isZero();
    }

    @Test
    void invalidSpec() {
        HttpResponse<String> resp = http.check(tenant, apiKey,
                ContractHttp.checkBody("billing", "swagger: '2.0'\ninfo: {title: x, version: '1'}\npaths: {}\n"));

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(422);
        assertThat(error(resp)).isEqualTo("INVALID_SPEC");
        assertThat(objectMapper.readTree(resp.body()).get("error").get("details").get("messages").size())
                .isGreaterThan(0);

        HttpResponse<String> noSpec = http.check(tenant, apiKey, ContractHttp.checkBody("billing", null));
        assertThat(noSpec.statusCode()).as(noSpec.body()).isEqualTo(400);
        assertThat(error(noSpec)).isEqualTo("VALIDATION_ERROR");
        assertThat(rows(tenant)).isZero();
    }

    private void assertNotFound(String body) {
        HttpResponse<String> resp = http.check(tenant, apiKey, body);
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(404);
        assertThat(error(resp)).isEqualTo("SERVICE_NOT_FOUND");
        assertThat(rows(tenant)).isZero();
    }

    private String error(HttpResponse<String> resp) {
        return objectMapper.readTree(resp.body()).get("error").get("code").stringValue();
    }

    private JsonNode data(HttpResponse<String> resp) {
        return objectMapper.readTree(resp.body()).get("data");
    }

    private MapSqlParameterSource params() {
        return new MapSqlParameterSource("t", tenant);
    }

    private int rows(UUID t) {
        return jdbc.queryForObject("SELECT count(*) FROM contract_checks WHERE tenant_id = :t",
                new MapSqlParameterSource("t", t), Integer.class);
    }

    private int versions(UUID t) {
        return jdbc.queryForObject("SELECT count(*) FROM contract_versions WHERE tenant_id = :t",
                new MapSqlParameterSource("t", t), Integer.class);
    }

    private void liveService(UUID t, UUID id, String name) {
        jdbc.update("INSERT INTO services (tenant_id, service_id, name) VALUES (:t, :s, :n)",
                new MapSqlParameterSource("t", t).addValue("s", id).addValue("n", name));
    }

    private void edge(UUID t, UUID source, UUID target) {
        jdbc.update("""
                INSERT INTO inbound_edges (tenant_id, edge_id, source_service_id, target_service_id, dependency_type,
                                           protocol)
                VALUES (:t, gen_random_uuid(), :s, :g, 'declared', 'http')
                """, new MapSqlParameterSource("t", t).addValue("s", source).addValue("g", target));
    }
}
