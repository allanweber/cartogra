package io.cartogra.registry;

import com.fasterxml.jackson.databind.JsonNode;
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
class TeamCrudIT {

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
    void createReadUpdateDelete() throws Exception {
        HttpResponse<String> created = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"platform-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode data = MAPPER.readTree(created.body()).get("data");
        String teamId = data.get("id").asText();
        assertThat(data.get("name").asText()).isEqualTo("platform-team");
        assertThat(created.headers().firstValue("X-Trace-Id")).isPresent();

        HttpResponse<String> got = HTTP.send(
                get("/api/v1/teams/" + teamId), HttpResponse.BodyHandlers.ofString());
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(got.body()).get("data").get("name").asText()).isEqualTo("platform-team");

        HttpResponse<String> updated = HTTP.send(
                put("/api/v1/teams/" + teamId, """
                        {"name":"platform-engineering"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(updated.body()).get("data").get("name").asText()).isEqualTo("platform-engineering");

        HttpResponse<String> deleted = HTTP.send(
                delete("/api/v1/teams/" + teamId), HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(204);

        HttpResponse<String> gone = HTTP.send(
                get("/api/v1/teams/" + teamId), HttpResponse.BodyHandlers.ofString());
        assertThat(gone.statusCode()).isEqualTo(404);
    }

    @Test
    void listTeams() throws Exception {
        HTTP.send(post("/api/v1/teams", """
                {"name":"list-team-alpha"}"""), HttpResponse.BodyHandlers.ofString());
        HTTP.send(post("/api/v1/teams", """
                {"name":"list-team-beta"}"""), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> list = HTTP.send(
                get("/api/v1/teams?limit=50&offset=0"), HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);
        JsonNode result = MAPPER.readTree(list.body()).get("data");
        assertThat(result.get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(result.get("items").isArray()).isTrue();
    }

    @Test
    void missingTeamReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                get("/api/v1/teams/" + UUID.randomUUID()), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void updateNonExistentTeamReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                put("/api/v1/teams/" + UUID.randomUUID(), """
                        {"name":"ghost-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void deleteNonExistentTeamReturns404() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                delete("/api/v1/teams/" + UUID.randomUUID()), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void duplicateTeamNameReturns409() throws Exception {
        HTTP.send(post("/api/v1/teams", """
                {"name":"dup-team-x"}"""), HttpResponse.BodyHandlers.ofString());

        HttpResponse<String> dup = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"dup-team-x"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(dup.body()).get("error").get("code").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void renameToExistingTeamNameReturns409() throws Exception {
        HTTP.send(post("/api/v1/teams", """
                {"name":"rename-target-team"}"""), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = HTTP.send(
                post("/api/v1/teams", """
                        {"name":"rename-source-team"}"""), HttpResponse.BodyHandlers.ofString());
        String secondId = MAPPER.readTree(second.body()).get("data").get("id").asText();

        HttpResponse<String> resp = HTTP.send(
                put("/api/v1/teams/" + secondId, """
                        {"name":"rename-target-team"}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(409);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("CONFLICT");
    }

    @Test
    void blankTeamNameReturns400() throws Exception {
        HttpResponse<String> resp = HTTP.send(
                post("/api/v1/teams", """
                        {"name":""}"""), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(400);
        assertThat(MAPPER.readTree(resp.body()).get("error").get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT.toString())
                .GET()
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

    private HttpRequest delete(String path) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", TENANT.toString())
                .DELETE()
                .build();
    }
}
