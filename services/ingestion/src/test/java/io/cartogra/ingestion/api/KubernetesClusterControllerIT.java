package io.cartogra.ingestion.api;

import io.cartogra.ingestion.infrastructure.k8s.ClusterWorkerManager;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
import io.cartogra.ingestion.infrastructure.kafka.SyncResultProducer;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=localhost:9092")
class KubernetesClusterControllerIT {

    @MockitoBean
    ClusterWorkerManager clusterWorkerManager;

    @MockitoBean
    SyncResultProducer syncResultProducer;

    @MockitoBean
    SyncCommandConsumer syncCommandConsumer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private static final UUID TENANT = UUID.randomUUID();

    // ── invalid kubeconfig cases ──────────────────────────────────────────────

    @Test
    void post_kubeconfigMissingClustersSection_returns400WithClearMessage() throws Exception {
        String body = kubeconfigBody("test", escape("""
                kind: Config
                users:
                - name: kind-cartogra
                  user:
                    client-certificate-data: dGVzdA==
                    client-key-data: dGVzdA==
                """));

        var res = post("/k8s/clusters", body);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"BAD_REQUEST\"");
        assertThat(res.body()).contains("clusters");
    }

    @Test
    void post_kubeconfigMissingCurrentContext_returns400WithClearMessage() throws Exception {
        String body = kubeconfigBody("test", escape("""
                apiVersion: v1
                clusters:
                - cluster:
                    server: https://127.0.0.1:6443
                  name: local
                users:
                - name: admin
                  user:
                    token: tok
                """));

        var res = post("/k8s/clusters", body);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"BAD_REQUEST\"");
        assertThat(res.body()).contains("current-context");
    }

    @Test
    void post_kubeconfigClusterMissingServer_returns400WithClearMessage() throws Exception {
        String body = kubeconfigBody("test", escape("""
                apiVersion: v1
                clusters:
                - cluster: {}
                  name: local
                current-context: local
                users:
                - name: admin
                  user:
                    token: tok
                """));

        var res = post("/k8s/clusters", body);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"BAD_REQUEST\"");
        assertThat(res.body()).contains("server");
    }

    @Test
    void post_notValidYaml_returns400WithClearMessage() throws Exception {
        String body = """
                {"name":"test","source":"KUBECONFIG","kubeconfig":"not: valid: yaml: ["}""";

        var res = post("/k8s/clusters", body);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"BAD_REQUEST\"");
        assertThat(res.body()).containsIgnoringCase("yaml");
    }

    @Test
    void post_manualMissingApiServerUrl_returns400WithClearMessage() throws Exception {
        String body = """
                {"name":"test","source":"MANUAL"}""";

        var res = post("/k8s/clusters", body);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"BAD_REQUEST\"");
        assertThat(res.body()).contains("apiServerUrl");
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void post_validKubeconfig_returns201AndEnvelope() throws Exception {
        String body = kubeconfigBody("local", escape("""
                apiVersion: v1
                clusters:
                - cluster:
                    server: https://127.0.0.1:6443
                    certificate-authority-data: dGVzdA==
                  name: local
                contexts:
                - context:
                    cluster: local
                    user: admin
                  name: local
                current-context: local
                users:
                - name: admin
                  user:
                    token: my-token
                """));

        var res = post("/k8s/clusters", body);

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"name\":\"local\"");
        assertThat(res.body()).contains("\"CONNECTING\"");
        assertThat(res.headers().firstValue("X-Trace-Id")).isPresent();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/ingestion" + path))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", TENANT.toString())
                .header("X-User-Roles", "ADMIN")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String kubeconfigBody(String name, String escapedYaml) {
        return "{\"name\":\"%s\",\"source\":\"KUBECONFIG\",\"kubeconfig\":\"%s\"}".formatted(name, escapedYaml);
    }

    private static String escape(String yaml) {
        return yaml.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "");
    }
}
