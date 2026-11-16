package io.cartogra.contract;

import io.cartogra.test.OpenApiContractValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
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
