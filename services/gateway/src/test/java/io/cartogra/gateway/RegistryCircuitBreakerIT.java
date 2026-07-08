package io.cartogra.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.redis.testcontainers.RedisContainer;
import io.cartogra.gateway.infrastructure.jwt.JwtClaims;
import io.cartogra.gateway.infrastructure.jwt.JwtTokenProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the real app via {@code SpringApplicationBuilder}. Both this and {@code @SpringBootTest}
 * resolve the classpath resource {@code application.yml} to {@code src/test/resources} (Gradle's
 * test classpath puts test resources ahead of main resources, so the plain classpath lookup never
 * reaches the main file at all — confirmed empirically, not merged, fully shadowed). {@code
 * spring.config.additional-location} is used to load the real {@code src/main/resources/application.yml}
 * from disk by file path instead, so the {@code registry} route's actual production {@code
 * CircuitBreaker} filter is exercised as-is rather than reimplemented in test args. The route's
 * {@code uri} is then redirected to WireMock via the same {@code REGISTRY_URI} placeholder the
 * route already uses.
 */
class RegistryCircuitBreakerIT {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("cartogra_test")
            .withUsername("cartogra")
            .withPassword("cartogra");

    @SuppressWarnings("resource")
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private ConfigurableApplicationContext context;
    private int port;

    @BeforeEach
    void startApp() {
        context = new SpringApplicationBuilder(GatewayApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.config.additional-location=file:src/main/resources/application.yml",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--REGISTRY_URI=" + wireMock.baseUrl(),
                        "--app.jwt.secret=test-secret-32-bytes-padding-here!",
                        "--app.jwt.access-token-expiry-seconds=900",
                        "--app.jwt.refresh-token-expiry-seconds=2592000",
                        "--app.frontend.base-url=http://localhost:3000",
                        "--app.cors.allowed-origins=http://localhost:3000",
                        "--app.resend.api-key=test-key",
                        "--app.resend.from-address=noreply@test.com",
                        "--app.resend.test-mode=true",
                        "--app.oauth.google.client-id=test-google-client",
                        "--app.oauth.google.client-secret=test-google-secret",
                        "--app.oauth.github.client-id=test-github-client",
                        "--app.oauth.github.client-secret=test-github-secret",
                        "--app.rate-limit.enabled=false");
        port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterEach
    void stopApp() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    void breakerOpensAfterRepeatedFailuresAndSurfacesEnvelopeError() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/api/v1/registry/.*"))
                .willReturn(aResponse().withStatus(500)));

        String token = context.getBean(JwtTokenProvider.class).issueAccessToken(new JwtClaims(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "circuitbreaker-it@test.com",
                "Circuit Breaker IT",
                "local",
                List.of("VIEWER"),
                Instant.now().plusSeconds(900)));

        HttpClient client = HttpClient.newHttpClient();

        // Drive enough failures to satisfy minimum-number-of-calls (5) at 100% failure rate,
        // which exceeds the configured failure-rate-threshold (50%) and opens the breaker.
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> response = client.send(registryRequest(token), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
        }

        wireMock.verify(exactly(5), getRequestedFor(urlPathMatching("/api/v1/registry/.*")));

        // Breaker should now be OPEN: the next call must short-circuit before reaching WireMock.
        HttpResponse<String> shortCircuited =
                client.send(registryRequest(token), HttpResponse.BodyHandlers.ofString());

        assertThat(shortCircuited.statusCode()).isEqualTo(503);
        assertThat(shortCircuited.body()).contains("\"code\":\"SERVICE_UNAVAILABLE\"");
        assertThat(shortCircuited.headers().firstValue("X-Trace-Id")).isPresent();
        wireMock.verify(exactly(5), getRequestedFor(urlPathMatching("/api/v1/registry/.*")));

        HttpResponse<String> health = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(health.body()).contains("\"registry\"");
        assertThat(health.body()).contains("\"circuitBreakerState\":\"OPEN\"");
    }

    private HttpRequest registryRequest(String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/services"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
    }
}
