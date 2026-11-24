package io.cartogra.contract;

import io.cartogra.web.security.InternalTokens;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Calls the contract API on a running test server as a gateway-proxied user would. */
public final class ContractHttp {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final int port;

    public ContractHttp(int port) {
        this.port = port;
    }

    public HttpResponse<String> create(UUID tenantId, UUID serviceId, String spec) {
        return post("", tenantId, "ADMIN", body(serviceId, spec));
    }

    public HttpResponse<String> publish(UUID tenantId, String contractId, String spec) {
        return post("/" + contractId + "/versions", tenantId, "ADMIN", body(null, spec));
    }

    public HttpResponse<String> get(UUID tenantId, String contractId) {
        return send(base(("/" + contractId), tenantId, "ADMIN").GET().build());
    }

    /** GET {@code path} under {@code /api/v1/contracts}, as a guest: reads need no write role. */
    public HttpResponse<String> read(UUID tenantId, String path) {
        return send(base(path, tenantId, "GUEST").GET().build());
    }

    public HttpResponse<String> post(String path, UUID tenantId, String role, String body) {
        return send(base(path, tenantId, role).POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    /** POST /check with an API-key token (role CI_CHECK, subject {@code apikey:<id>}). */
    public HttpResponse<String> check(UUID tenantId, UUID apiKeyId, String body) {
        return check("/check", tenantId, apiKeyId, body);
    }

    /** Same as {@link #check(UUID, UUID, String)} on another path, for example {@code /check/}. */
    public HttpResponse<String> check(String path, UUID tenantId, UUID apiKeyId, String body) {
        String token = InternalTokens.codec(java.time.Clock.systemUTC()).sign(
                new io.cartogra.web.security.InternalClaims(tenantId, "apikey:" + apiKeyId, java.util.List.of("CI_CHECK")),
                "contract");
        return send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/contracts" + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .header(InternalTokens.HEADER, token)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    public static String checkBody(String service, String spec) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (service != null) {
            body.put("service", service);
        }
        if (spec != null) {
            body.put("spec", spec);
        }
        return JSON.writeValueAsString(body);
    }

    public static String body(UUID serviceId, String spec) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (serviceId != null) {
            body.put("serviceId", serviceId);
        }
        body.put("spec", spec);
        return JSON.writeValueAsString(body);
    }

    private HttpRequest.Builder base(String path, UUID tenantId, String role) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/contracts" + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", tenantId.toString())
                .header(InternalTokens.HEADER, InternalTokens.token("contract", tenantId, UUID.randomUUID(), role));
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
