package io.cartogra.contract;

import io.cartogra.test.OpenApiContractValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ContractEnvelopeContractTest extends AbstractContractIT {

    private static final String SPEC = """
            openapi: 3.1.0
            info: {title: Envelope, version: '1'}
            paths: {}
            """;

    private final OpenApiContractValidator validator = new OpenApiContractValidator("contract.openapi.yaml");
    private final ObjectMapper objectMapper = new ObjectMapper();

    @LocalServerPort
    int port;

    ContractHttp http;
    UUID tenant;

    @BeforeEach
    void setUp() {
        http = new ContractHttp(port);
        tenant = UUID.randomUUID();
    }

    @Test
    void create201() {
        assertConforms("POST", "/api/v1/contracts", http.create(tenant, UUID.randomUUID(), SPEC), 201);
    }

    @Test
    void create409() {
        UUID serviceId = UUID.randomUUID();
        http.create(tenant, serviceId, SPEC);
        assertConforms("POST", "/api/v1/contracts", http.create(tenant, serviceId, SPEC), 409);
    }

    @Test
    void create422() {
        assertConforms("POST", "/api/v1/contracts", http.create(tenant, UUID.randomUUID(), "swagger: '2.0'"), 422);
    }

    @Test
    void create400() {
        assertConforms("POST", "/api/v1/contracts", http.create(tenant, UUID.randomUUID(), ""), 400);
    }

    @Test
    void get200And404() {
        String id = contractId();
        assertConforms("GET", "/api/v1/contracts/" + id, http.get(tenant, id), 200);
        assertConforms("GET", "/api/v1/contracts/" + id, http.get(UUID.randomUUID(), id), 404);
    }

    @Test
    void publish201And200() {
        String id = contractId();
        String path = "/api/v1/contracts/" + id + "/versions";
        assertConforms("POST", path, http.publish(tenant, id, SPEC.replace("version: '1'", "version: '2'")), 201);
        assertConforms("POST", path, http.publish(tenant, id, SPEC.replace("version: '1'", "version: '2'")), 200);
    }

    @Test
    void publish403ForGuest() {
        String id = contractId();
        String path = "/api/v1/contracts/" + id + "/versions";
        HttpResponse<String> resp = http.post("/" + id + "/versions", tenant, "GUEST", ContractHttp.body(null, SPEC));
        assertConforms("POST", path, resp, 403);
    }

    @Test
    void list200() {
        contractId();
        assertConforms("GET", "/api/v1/contracts", http.read(tenant, ""), 200);
        assertConforms("GET", "/api/v1/contracts", http.read(tenant, "?status=compatible"), 200);
    }

    @Test
    void list400ForUnknownStatus() {
        assertConforms("GET", "/api/v1/contracts", http.read(tenant, "?status=nope"), 400);
    }

    @Test
    void diff200WithBreakingCompatibility() {
        String id = contractId();
        http.publish(tenant, id, SPEC.replace("paths: {}", """
                paths:
                  /a:
                    get:
                      parameters:
                        - {name: q, in: query, required: true, schema: {type: string}}
                      responses:
                        '200': {description: ok}"""));
        http.publish(tenant, id, SPEC.replace("version: '1'", "version: '3'"));
        assertConforms("GET", "/api/v1/contracts/" + id + "/versions/3/diff",
                http.read(tenant, "/" + id + "/versions/3/diff"), 200);
    }

    @Test
    void versions200And404() {
        String id = contractId();
        String path = "/api/v1/contracts/" + id + "/versions";
        assertConforms("GET", path, http.read(tenant, "/" + id + "/versions"), 200);
        assertConforms("GET", path, http.read(UUID.randomUUID(), "/" + id + "/versions"), 404);
    }

    @Test
    void diff200And404() {
        String id = contractId();
        http.publish(tenant, id, SPEC.replace("version: '1'", "version: '2'"));
        assertConforms("GET", "/api/v1/contracts/" + id + "/versions/2/diff",
                http.read(tenant, "/" + id + "/versions/2/diff"), 200);
        assertConforms("GET", "/api/v1/contracts/" + id + "/versions/1/diff",
                http.read(tenant, "/" + id + "/versions/1/diff"), 200);
        assertConforms("GET", "/api/v1/contracts/" + id + "/versions/5/diff",
                http.read(tenant, "/" + id + "/versions/5/diff"), 404);
    }

    @Test
    void consumers200And404() {
        String id = contractId();
        String path = "/api/v1/contracts/" + id + "/consumers";
        assertConforms("GET", path, http.read(tenant, "/" + id + "/consumers"), 200);
        assertConforms("GET", path, http.read(UUID.randomUUID(), "/" + id + "/consumers"), 404);
    }

    @Test
    void ruleEndpointsMatchOpenApi() {
        String listPath = "/api/v1/contracts/notification-rules";
        UUID serviceId = UUID.randomUUID();
        String valid = ContractHttp.notificationRuleBody(serviceId, "http://127.0.0.1:1/hook", null, List.of(), true);

        HttpResponse<String> created = http.createNotificationRule(tenant, "ADMIN", valid);
        assertConforms("POST", listPath, created, 201);
        String id = objectMapper.readTree(created.body()).get("data").get("id").stringValue();
        String itemPath = listPath + "/" + id;

        assertConforms("GET", itemPath, http.notificationRules(tenant, "/" + id), 200);
        assertConforms("GET", listPath, http.notificationRules(tenant, ""), 200);
        assertConforms("PUT", itemPath, http.putNotificationRule(tenant, "ADMIN", "/" + id, valid), 200);
        assertConforms("POST", listPath, http.createNotificationRule(tenant, "ADMIN", valid), 409);

        String invalid = ContractHttp.notificationRuleBody(serviceId, "http://example.com/hook", null, List.of(), true);
        assertConforms("POST", listPath, http.createNotificationRule(tenant, "ADMIN", invalid), 400);
        assertConforms("POST", listPath, http.createNotificationRule(tenant, "GUEST", valid), 403);
        UUID unknown = UUID.randomUUID();
        assertConforms("GET", listPath + "/" + unknown, http.notificationRules(tenant, "/" + unknown), 404);

        assertConforms("POST", itemPath + "/disable", http.postNotificationRule(tenant, "ADMIN", "/" + id + "/disable", ""), 200);
        assertConforms("POST", itemPath + "/test", http.postNotificationRule(tenant, "ADMIN", "/" + id + "/test", ""), 409);
        assertConforms("POST", itemPath + "/enable", http.postNotificationRule(tenant, "ADMIN", "/" + id + "/enable", ""), 200);
        assertConforms("POST", itemPath + "/test", http.postNotificationRule(tenant, "ADMIN", "/" + id + "/test", ""), 200);
        assertConforms("GET", itemPath + "/attempts", http.notificationRules(tenant, "/" + id + "/attempts"), 200);
    }

    private String contractId() {
        HttpResponse<String> resp = http.create(tenant, UUID.randomUUID(), SPEC);
        return objectMapper.readTree(resp.body()).get("data").get("id").stringValue();
    }

    private void assertConforms(String method, String path, HttpResponse<String> resp, int expectedStatus) {
        assertThat(resp.statusCode()).as(resp.body()).isEqualTo(expectedStatus);
        Map<String, String> headers = resp.headers().map().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getFirst()));
        validator.assertResponse(method, path, resp.statusCode(), resp.body(), headers);
    }
}
