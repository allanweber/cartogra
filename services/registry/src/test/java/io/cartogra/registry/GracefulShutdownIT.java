package io.cartogra.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.cartogra.shutdowntest.SlowEndpointConfig;
import io.cartogra.test.PostgresTestSupport;
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
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the app the same way {@code SpringApplication.run()} does in production (not via
 * {@code @SpringBootTest}'s cached context), so calling {@code context.close()} here faithfully
 * simulates a real SIGTERM-triggered graceful shutdown without interference from Spring's
 * test-context caching/listener machinery.
 */
class GracefulShutdownIT {

    private ConfigurableApplicationContext context;

    @AfterEach
    void closeIfStillOpen() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    void inFlightHttpRequestFinishesCleanlyDuringGracefulShutdown() throws Exception {
        SlowEndpointConfig.HANDLER_STARTED.set(false);

        context = new SpringApplicationBuilder(RegistryApplication.class, SlowEndpointConfig.class)
                .properties(
                        "server.port=0",
                        "spring.flyway.enabled=false",
                        "spring.datasource.url=" + PostgresTestSupport.POSTGRES.getJdbcUrl(),
                        "spring.datasource.username=" + PostgresTestSupport.POSTGRES.getUsername(),
                        "spring.datasource.password=" + PostgresTestSupport.POSTGRES.getPassword())
                .run();

        int port = ((WebServerApplicationContext) context).getWebServer().getPort();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/registry/test/slow"))
                .GET()
                .build();

        CompletableFuture<HttpResponse<String>> inFlight =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString());

        await().atMost(Duration.ofSeconds(5))
                .untilTrue(SlowEndpointConfig.HANDLER_STARTED);

        Instant closeStart = Instant.now();
        context.close();
        Duration closeDuration = Duration.between(closeStart, Instant.now());

        // Tomcat's graceful shutdown must wait for the in-flight request to finish
        // (up to the 30s shutdown-phase timeout), not cut it off immediately.
        assertThat(closeDuration).isGreaterThanOrEqualTo(Duration.ofMillis(2500));

        HttpResponse<String> response = inFlight.get(5, TimeUnit.SECONDS);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("done");
    }
}
