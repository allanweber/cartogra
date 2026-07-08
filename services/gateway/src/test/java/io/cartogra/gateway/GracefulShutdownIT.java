package io.cartogra.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.redis.testcontainers.RedisContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the app the same way {@code SpringApplication.run()} does in production (not via
 * {@code @SpringBootTest}'s cached context), so calling {@code context.close()} here faithfully
 * simulates a real SIGTERM-triggered graceful shutdown without interference from Spring's
 * test-context caching/listener machinery.
 *
 * <p>Uses a real proxied route to a slow WireMock backend rather than a local controller, since
 * gateway's routing/filter chain intercepts requests ahead of any locally-registered
 * {@code @RestController} - this mirrors the actual in-flight-request scenario (a slow downstream
 * service), not gateway-local logic.
 */
class GracefulShutdownIT {

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

    @AfterEach
    void closeIfStillOpen() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    void inFlightProxiedRequestFinishesCleanlyDuringGracefulShutdown() throws Exception {
        wireMock.stubFor(get(urlPathMatching("/api/auth/oauth/test-slow"))
                .willReturn(okJson("{\"done\":true}").withFixedDelay(3000)));

        context = new SpringApplicationBuilder(GatewayApplication.class)
                .run(
                        "--server.port=0",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--spring.cloud.gateway.server.webmvc.routes[0].id=test-slow",
                        "--spring.cloud.gateway.server.webmvc.routes[0].uri=" + wireMock.baseUrl(),
                        "--spring.cloud.gateway.server.webmvc.routes[0].predicates[0]=Path=/api/auth/oauth/test-slow",
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

        int port = ((WebServerApplicationContext) context).getWebServer().getPort();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/auth/oauth/test-slow"))
                .GET()
                .build();

        CompletableFuture<HttpResponse<String>> inFlight =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                wireMock.verify(getRequestedFor(urlPathMatching("/api/auth/oauth/test-slow"))));

        Instant closeStart = Instant.now();
        context.close();
        Duration closeDuration = Duration.between(closeStart, Instant.now());

        // Tomcat's graceful shutdown must wait for the in-flight proxied request to finish
        // (up to the 30s shutdown-phase timeout), not cut it off immediately.
        assertThat(closeDuration).isGreaterThanOrEqualTo(Duration.ofMillis(1500));

        HttpResponse<String> response = inFlight.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"done\":true");
    }
}
