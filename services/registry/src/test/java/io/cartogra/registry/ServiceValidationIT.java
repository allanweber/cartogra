package io.cartogra.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServiceValidationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final UUID TENANT = UUID.randomUUID();

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @LocalServerPort
    private int port;

    @Test
    void blankServiceNameReturns400() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                post("/api/v1/services", """
                        {"name":""}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void duplicateServiceNameReturns409() throws Exception {
        HTTP.send(post("/api/v1/services", """
                {"name":"dup-svc-name"}"""), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> dup = HTTP.send(
                post("/api/v1/services", """
                        {"name":"dup-svc-name"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(dup.body()).get("error").get("code").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void updateToExistingServiceNameReturns409() throws Exception {
        HTTP.send(post("/api/v1/services", """
                {"name":"svc-rename-target"}"""), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = HTTP.send(
                post("/api/v1/services", """
                        {"name":"svc-rename-source"}"""), HttpResponse.BodyHandlers.ofString());
        String secondId = MAPPER.readTree(second.body()).get("data").get("id").asText();

        HttpResponse<String> resp = HTTP.send(
                put("/api/v1/services/" + secondId, """
                        {"name":"svc-rename-target","description":"updated"}"""),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void missingTenantIdHeaderReturns400() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/services"))
                .header("Content-Type", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void updateNonExistentServiceReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                put("/api/v1/services/" + UUID.randomUUID(), """
                        {"name":"ghost-svc","description":"does not exist"}"""),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest put(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }
}
