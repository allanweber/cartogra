package io.cartogra.contract.api;

import io.cartogra.contract.AbstractContractIT;
import io.cartogra.contract.ContractHttp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContractReadIT extends AbstractContractIT {

    static final String V1 = ContractControllerIT.V1;
    static final String V2 = V1.replace("version: 1.0.0", "version: 1.1.0") + """
              /entries/{id}:
                get:
                  parameters:
                    - name: id
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: ok
            """;

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    ContractHttp http;
    UUID tenant;

    @BeforeEach
    void setUp() {
        http = new ContractHttp(port);
        tenant = UUID.randomUUID();
    }

    @Test
    void listReturnsLatestVersionAndServiceNameNewestFirst() {
        UUID ledger = UUID.randomUUID();
        UUID payments = UUID.randomUUID();
        nameService(ledger, "ledger-service");
        String ledgerId = create(ledger, V1);
        String paymentsId = create(payments, V1);
        forgetName(payments);
        assertThat(http.publish(tenant, ledgerId, V2).statusCode()).isEqualTo(201);

        JsonNode page = data(http.read(tenant, ""));

        assertThat(page.get("total").asLong()).isEqualTo(2);
        JsonNode first = page.get("items").get(0);
        assertThat(first.get("id").stringValue()).isEqualTo(ledgerId);
        assertThat(first.get("serviceName").stringValue()).isEqualTo("ledger-service");
        assertThat(first.get("latestVersion").get("versionNumber").asInt()).isEqualTo(2);
        assertThat(first.get("latestVersion").get("label").stringValue()).isEqualTo("1.1.0");
        JsonNode second = page.get("items").get(1);
        assertThat(second.get("id").stringValue()).isEqualTo(paymentsId);
        assertThat(second.get("serviceName").isNull()).isTrue();
    }

    @Test
    void listPaginatesSearchesByNameAndIsolatesTenants() {
        for (int i = 0; i < 3; i++) {
            UUID serviceId = UUID.randomUUID();
            nameService(serviceId, "svc_" + i);
            create(serviceId, V1);
        }
        UUID other = UUID.randomUUID();
        nameService(other, "zeta");
        create(other, V1);
        new ContractHttp(port).create(UUID.randomUUID(), UUID.randomUUID(), V1);

        JsonNode page = data(http.read(tenant, "?limit=2&offset=2"));
        assertThat(page.get("total").asLong()).isEqualTo(4);
        assertThat(page.get("limit").asInt()).isEqualTo(2);
        assertThat(page.get("items").size()).isEqualTo(2);

        JsonNode search = data(http.read(tenant, "?q=SVC_"));
        assertThat(search.get("total").asLong()).isEqualTo(3);
        assertThat(data(http.read(tenant, "?q=%25")).get("total").asLong()).isZero();
    }

    @Test
    void statusFiltersOnTheLatestVersionAndPages() {
        List<String> breaking = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String id = create(UUID.randomUUID(), V2);
            assertThat(http.publish(tenant, id, V1).statusCode()).isEqualTo(201);
            breaking.add(id);
        }
        String compatible = create(UUID.randomUUID(), V1);
        http.publish(tenant, compatible, V2);
        String unchecked = create(UUID.randomUUID(), V1);
        jdbc.update("UPDATE contract_versions SET is_breaking = NULL WHERE tenant_id = :t AND contract_id = :c",
                new MapSqlParameterSource().addValue("t", tenant).addValue("c", UUID.fromString(unchecked)));
        String otherTenant = data(new ContractHttp(port).create(UUID.randomUUID(), UUID.randomUUID(), V2))
                .get("id").stringValue();

        JsonNode page = data(http.read(tenant, "?status=breaking&limit=2&offset=0"));
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("items").size()).isEqualTo(2);
        JsonNode rest = data(http.read(tenant, "?status=breaking&limit=2&offset=2"));
        assertThat(rest.get("items").size()).isEqualTo(1);
        List<String> ids = new ArrayList<>();
        page.get("items").forEach(c -> ids.add(c.get("id").stringValue()));
        rest.get("items").forEach(c -> ids.add(c.get("id").stringValue()));
        assertThat(ids).containsExactlyInAnyOrderElementsOf(breaking);
        page.get("items").forEach(c -> assertThat(c.get("latestVersion").get("breaking").booleanValue()).isTrue());

        JsonNode compat = data(http.read(tenant, "?status=compatible"));
        assertThat(compat.get("total").asLong()).isEqualTo(1);
        assertThat(compat.get("items").get(0).get("id").stringValue()).isEqualTo(compatible).isNotEqualTo(otherTenant);

        JsonNode all = data(http.read(tenant, ""));
        assertThat(all.get("total").asLong()).isEqualTo(5);
        all.get("items").forEach(c -> {
            if (c.get("id").stringValue().equals(unchecked)) {
                assertThat(c.get("latestVersion").get("breaking").isNull()).isTrue();
            }
        });
    }

    @Test
    void anUnknownStatusIsAValidationError() {
        HttpResponse<String> resp = http.read(tenant, "?status=nope");

        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode body = objectMapper.readTree(resp.body());
        assertThat(body.get("error").get("code").stringValue()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("error").get("details").has("status")).isTrue();
        assertThat(body.get("traceId").stringValue()).matches("[0-9a-f]{32}")
                .isEqualTo(resp.headers().firstValue("X-Trace-Id").orElseThrow());
    }

    @Test
    void listOmitsContractsOfDeletedServices() {
        UUID serviceId = UUID.randomUUID();
        create(serviceId, V1);
        jdbc.update("UPDATE api_contracts SET deleted_at = now() WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant));

        assertThat(data(http.read(tenant, "")).get("total").asLong()).isZero();
    }

    @Test
    void versionsAreNewestFirstAndPaginated() {
        String id = create(UUID.randomUUID(), V1);
        http.publish(tenant, id, V2);

        JsonNode page = data(http.read(tenant, "/" + id + "/versions"));
        assertThat(page.get("total").asLong()).isEqualTo(2);
        List<Integer> numbers = new ArrayList<>();
        page.get("items").forEach(v -> numbers.add(v.get("versionNumber").asInt()));
        assertThat(numbers).containsExactly(2, 1);
        page.get("items").forEach(v -> assertThat(v.get("breaking").booleanValue()).isFalse());
        assertThat(data(http.read(tenant, "/" + id + "/versions?limit=1&offset=1")).get("items").get(0)
                .get("versionNumber").asInt()).isEqualTo(1);
        assertThat(http.read(UUID.randomUUID(), "/" + id + "/versions").statusCode()).isEqualTo(404);
    }

    @Test
    void diffOfVersionTwoReportsTheAddedPathAndLines() {
        String id = create(UUID.randomUUID(), V1);
        http.publish(tenant, id, V2);

        HttpResponse<String> resp = http.read(tenant, "/" + id + "/versions/2/diff");

        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(200);
        JsonNode diff = data(resp);
        assertThat(diff.get("from").get("versionNumber").asInt()).isEqualTo(1);
        assertThat(diff.get("to").get("versionNumber").asInt()).isEqualTo(2);
        List<String> changes = new ArrayList<>();
        diff.get("changes").forEach(c -> changes.add(c.get("op").stringValue() + " " + c.get("pointer").stringValue()));
        assertThat(changes).containsExactly("changed /info/version", "added /paths/~1entries~1{id}");
        List<String> added = new ArrayList<>();
        diff.get("lines").forEach(l -> {
            if ("added".equals(l.get("kind").stringValue())) {
                added.add(l.get("right").stringValue());
            }
        });
        assertThat(added).anyMatch(line -> line.contains("/entries/{id}"));
        JsonNode compatibility = diff.get("compatibility");
        assertThat(compatibility.get("isBreaking").booleanValue()).isFalse();
        JsonNode hit = compatibility.get("changes").get(0);
        assertThat(compatibility.get("changes").size()).isEqualTo(1);
        assertThat(hit.get("rule").stringValue()).isEqualTo("path-added");
        assertThat(hit.get("breaking").booleanValue()).isFalse();
        assertThat(hit.get("direction").stringValue()).isEqualTo("operation");
        assertThat(hit.get("path").stringValue()).isEqualTo("/entries/{id}");
        assertThat(diff.get("to").get("breaking").booleanValue()).isFalse();
    }

    @Test
    void diffComputesCompatibilityForAnUncheckedVersion() {
        String id = create(UUID.randomUUID(), V2);
        http.publish(tenant, id, V1);
        jdbc.update("UPDATE contract_versions SET is_breaking = NULL WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant));

        JsonNode diff = data(http.read(tenant, "/" + id + "/versions/2/diff"));

        assertThat(diff.get("to").get("breaking").isNull()).isTrue();
        assertThat(diff.get("compatibility").get("isBreaking").booleanValue()).isTrue();
        assertThat(diff.get("compatibility").get("changes").get(0).get("rule").stringValue())
                .isEqualTo("path-removed");
        assertThat(data(http.read(tenant, "?status=breaking")).get("total").asLong()).isZero();
        assertThat(data(http.read(tenant, "?status=compatible")).get("total").asLong()).isZero();
    }

    @Test
    void diffOfVersionOneIsAllAdded() {
        String id = create(UUID.randomUUID(), V1);

        JsonNode diff = data(http.read(tenant, "/" + id + "/versions/1/diff"));

        assertThat(diff.get("from").isNull()).isTrue();
        diff.get("lines").forEach(l -> assertThat(l.get("kind").stringValue()).isEqualTo("added"));
    }

    @Test
    void diffOfUnknownOrMalformedVersionIsAnError() {
        String id = create(UUID.randomUUID(), V1);

        assertThat(http.read(tenant, "/" + id + "/versions/9/diff").statusCode()).isEqualTo(404);
        assertThat(http.read(tenant, "/" + id + "/versions/x/diff").statusCode()).isEqualTo(400);
        assertThat(http.read(UUID.randomUUID(), "/" + id + "/versions/1/diff").statusCode()).isEqualTo(404);
    }

    @Test
    void listAsksRegistryOnceForServicesWithNoNameAndKeepsTheAnswer() {
        UUID payments = UUID.randomUUID();
        create(payments, V1);
        forgetName(payments);
        clearInvocations(registryNameClient);
        when(registryNameClient.findNames(eq(tenant), any())).thenReturn(Map.of(payments, "payments-service"));

        JsonNode first = data(http.read(tenant, "?q=payments")).get("items");
        JsonNode second = data(http.read(tenant, "")).get("items");

        assertThat(first.get(0).get("serviceName").stringValue()).isEqualTo("payments-service");
        assertThat(second.get(0).get("serviceName").stringValue()).isEqualTo("payments-service");
        verify(registryNameClient, times(1)).findNames(eq(tenant), eq(List.of(payments)));
        assertThat(jdbc.queryForObject("SELECT name FROM services WHERE tenant_id = :t AND service_id = :s",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", payments), String.class))
                .isEqualTo("payments-service");
    }

    @Test
    void namedServicesNeverReachRegistry() {
        UUID ledger = UUID.randomUUID();
        nameService(ledger, "ledger-service");
        String id = create(ledger, V1);

        data(http.read(tenant, ""));
        data(http.read(tenant, "/" + id));

        verify(registryNameClient, times(0)).findNames(any(), any());
    }

    @Test
    void registryDownLeavesTheNameEmpty() {
        UUID payments = UUID.randomUUID();
        String id = create(payments, V1);
        forgetName(payments);
        when(registryNameClient.findNames(eq(tenant), any())).thenThrow(new RestClientException("down"));

        JsonNode item = data(http.read(tenant, "")).get("items").get(0);
        JsonNode contract = data(http.read(tenant, "/" + id));

        assertThat(item.get("serviceName").isNull()).isTrue();
        assertThat(contract.get("serviceName").isNull()).isTrue();
    }

    /** Makes the service look like one created before contract stored names, so reads must fill the name in. */
    private void forgetName(UUID serviceId) {
        when(registryNameClient.findNames(eq(tenant), any())).thenReturn(Map.of());
        jdbc.update("UPDATE services SET name = NULL WHERE tenant_id = :t AND service_id = :s",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", serviceId));
    }

    private void nameService(UUID serviceId, String name) {
        jdbc.update("INSERT INTO services (tenant_id, service_id, name) VALUES (:t, :s, :n)",
                new MapSqlParameterSource().addValue("t", tenant).addValue("s", serviceId).addValue("n", name));
    }

    private String create(UUID serviceId, String spec) {
        HttpResponse<String> resp = http.create(tenant, serviceId, spec);
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(201);
        return data(resp).get("id").stringValue();
    }

    private JsonNode data(HttpResponse<String> resp) {
        assertThat(resp.statusCode()).as(resp.body()).isIn(200, 201);
        return objectMapper.readTree(resp.body()).get("data");
    }
}
