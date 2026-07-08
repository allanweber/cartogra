package io.cartogra.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.redis.testcontainers.RedisContainer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Registry gets the full WireMock-backed breaker-trip proof in {@link RegistryCircuitBreakerIT}.
 * Ingestion shares the identical Resilience4j "default" config block, so this only proves the
 * "ingestion" instance is wired with the same tuning — a second full IT would be redundant.
 *
 * <p>Boots the real app via {@code SpringApplicationBuilder}, loading the actual production
 * {@code src/main/resources/application.yml} by file path via {@code spring.config.additional-location}
 * — see {@link RegistryCircuitBreakerIT} for why the plain classpath resource is shadowed by
 * {@code src/test/resources/application.yml} and can't be relied on here.
 */
class IngestionCircuitBreakerConfigTest {

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
    void stopApp() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    void ingestionBreakerIsConfiguredWithTheSameDefaultsAsRegistry() {
        context = new SpringApplicationBuilder(GatewayApplication.class)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .run(
                        "--spring.config.additional-location=file:src/main/resources/application.yml",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
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

        CircuitBreakerRegistry circuitBreakerRegistry = context.getBean(CircuitBreakerRegistry.class);
        CircuitBreaker ingestion = circuitBreakerRegistry.circuitBreaker("ingestion");
        CircuitBreakerConfig config = ingestion.getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50f);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(10_000L);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(ingestion.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
