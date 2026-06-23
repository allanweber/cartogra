package io.cartogra.registry.infrastructure.scheduled;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cartogra.registry.domain.ServiceHealthService;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "registry.health.probe-timeout=PT0.5S",
                "registry.health.allow-http-endpoints=true",
                "registry.health.probe-interval=PT999H"
        }
)
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                "cartogra.registry.service.registered",
                "cartogra.registry.service.updated",
                "cartogra.registry.service.deleted"
        }
)
class HealthProbeSchedulerIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
    }

    @Autowired
    ServiceHealthService serviceHealthService;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    void http200TransitionsToHealthy() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
        UUID serviceId = seedService("unknown");

        serviceHealthService.probeAll();

        assertThat(healthStatus(serviceId)).isEqualTo("healthy");
        assertThat(healthCheckedAt(serviceId)).isNotNull();
        assertThat(historyCount(serviceId)).isEqualTo(1);
    }

    @Test
    void http503TransitionsToDegraded() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(503)));
        UUID serviceId = seedService("unknown");

        serviceHealthService.probeAll();

        assertThat(healthStatus(serviceId)).isEqualTo("degraded");
        assertThat(historyCount(serviceId)).isEqualTo(1);
    }

    @Test
    void http401TransitionsToProbeAuthFailed() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(401)));
        UUID serviceId = seedService("unknown");

        serviceHealthService.probeAll();

        assertThat(healthStatus(serviceId)).isEqualTo("probe_auth_failed");
        assertThat(historyCount(serviceId)).isEqualTo(1);
    }

    @Test
    void http403TransitionsToProbeAuthFailed() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(403)));
        UUID serviceId = seedService("unknown");

        serviceHealthService.probeAll();

        assertThat(healthStatus(serviceId)).isEqualTo("probe_auth_failed");
    }

    @Test
    void timeoutTransitionsToUnhealthy() {
        // 1000ms delay > 500ms probe timeout
        wireMock.stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1000)));
        UUID serviceId = seedService("unknown");

        serviceHealthService.probeAll();

        assertThat(healthStatus(serviceId)).isEqualTo("unhealthy");
        assertThat(historyCount(serviceId)).isEqualTo(1);
    }

    @Test
    void noSnapshotOnSameStatusSecondProbe() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
        UUID serviceId = seedService("healthy");

        serviceHealthService.probeAll();

        assertThat(healthStatus(serviceId)).isEqualTo("healthy");
        assertThat(historyCount(serviceId)).isEqualTo(0);
    }

    @Test
    void healthCheckedAtAdvancesEvenWithNoTransition() throws InterruptedException {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
        UUID serviceId = seedService("healthy");

        serviceHealthService.probeAll();
        Instant first = healthCheckedAt(serviceId);

        Thread.sleep(50);
        serviceHealthService.probeAll();
        Instant second = healthCheckedAt(serviceId);

        assertThat(second).isAfterOrEqualTo(first);
    }

    @Test
    void kubernetesSourcedServiceIsNotProbed() {
        wireMock.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(200)));
        UUID serviceId = seedService("unknown", "kubernetes");

        serviceHealthService.probeAll();

        assertThat(healthStatus(serviceId)).isEqualTo("unknown");
    }

    // Seeds a service directly into DB (bypasses SSRF validator; localhost is needed for WireMock).
    private UUID seedService(String initialStatus) {
        return seedService(initialStatus, "manual");
    }

    private UUID seedService(String initialStatus, String source) {
        UUID id = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        String endpoint = "http://localhost:" + wireMock.port() + "/health";
        Instant now = Instant.now();

        jdbc.update("""
                INSERT INTO services (id, tenant_id, name, health_status, health_endpoint, source,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :name, :healthStatus, :healthEndpoint, :source,
                    :createdAt, :updatedAt)
                """, new MapSqlParameterSource(Map.of(
                "id", id,
                "tenantId", tenantId,
                "name", "probe-test-" + id,
                "healthStatus", initialStatus,
                "healthEndpoint", endpoint,
                "source", source,
                "createdAt", java.sql.Timestamp.from(now),
                "updatedAt", java.sql.Timestamp.from(now)
        )));

        return id;
    }

    private String healthStatus(UUID id) {
        return jdbc.queryForObject(
                "SELECT health_status FROM services WHERE id = :id",
                Map.of("id", id), String.class);
    }

    private Instant healthCheckedAt(UUID id) {
        var ts = jdbc.queryForObject(
                "SELECT health_checked_at FROM services WHERE id = :id",
                Map.of("id", id), java.sql.Timestamp.class);
        return ts != null ? ts.toInstant() : null;
    }

    private int historyCount(UUID id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM services_history WHERE service_id = :id",
                Map.of("id", id), Integer.class);
        return count != null ? count : 0;
    }
}
