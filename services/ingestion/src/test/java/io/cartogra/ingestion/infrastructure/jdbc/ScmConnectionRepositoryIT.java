package io.cartogra.ingestion.infrastructure.jdbc;

import io.cartogra.ingestion.api.dto.ScmConnectionRequest;
import io.cartogra.ingestion.domain.ScmConnection;
import io.cartogra.ingestion.domain.ScmConnectionService;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandProducer;
import io.cartogra.ingestion.infrastructure.kafka.SyncResultProducer;
import io.cartogra.ingestion.infrastructure.registry.RegistryPlanLimitClient;
import io.cartogra.test.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Proves SCM connection secrets (token/pat/webhookSecret) are encrypted before ever reaching
 * the {@code scm_connections.config} JSONB column — see plans/012. Queries the raw column
 * directly via JDBC, bypassing {@link io.cartogra.ingestion.repository.ScmConnectionRepository}
 * accessors, so no application-side decryption can mask a plaintext-at-rest regression.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=localhost:9092")
class ScmConnectionRepositoryIT {

    // Any valid base64 AES key works here — this test only cares that ciphertext (whatever it
    // is) never contains the plaintext secret, not about a specific key/value.
    private static final String CREDENTIALS_KEY = "bKzA+h1MBYaoMzzkPtTsP7aBwzdgqm72w/yyhYmOfzg=";

    @MockitoBean
    SyncResultProducer syncResultProducer;

    @MockitoBean
    SyncCommandConsumer syncCommandConsumer;

    @MockitoBean
    SyncCommandProducer syncCommandProducer;

    @MockitoBean
    RegistryPlanLimitClient planLimitClient;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("ingestion.k8s.credentials-key", () -> CREDENTIALS_KEY);
        registry.add("ingestion.k8s.connection-timeout-ms", () -> "200");
        // This class needs its own credentials-key value (to exercise real AES encryption), so it
        // necessarily bootstraps a Spring context distinct from every other IT sharing the static
        // Postgres container — Spring's test context cache keeps every distinct context's Hikari
        // pool alive for the whole JVM run. With ~10 IT classes each opening a default-sized
        // (10-connection) pool against the same container, the shared server's max_connections gets
        // exhausted ("FATAL: sorry, too many clients already"). Keep this context's own pool tiny —
        // two connections is enough for these two sequential tests — so it doesn't push the shared
        // container over the edge.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @Autowired
    ScmConnectionService service;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void create_encryptsTokenAndWebhookSecretBeforeReachingRawColumn() {
        UUID tenantId = UUID.randomUUID();
        lenient().when(planLimitClient.fetchLimits(tenantId)).thenReturn(Optional.empty());

        String plaintextToken = "ghp_super-secret-value-12345";
        String plaintextWebhookSecret = "whsec_also-very-secret";
        ScmConnectionRequest req = new ScmConnectionRequest(
                "github",
                "{\"org\":\"acme\",\"token\":\"%s\",\"webhookSecret\":\"%s\"}"
                        .formatted(plaintextToken, plaintextWebhookSecret),
                null, null, true);

        ScmConnection saved = service.create(tenantId, req);

        String rawConfig = jdbc.queryForObject(
                "SELECT config::text FROM scm_connections WHERE id = :id",
                new MapSqlParameterSource("id", saved.id()), String.class);

        assertThat(rawConfig).doesNotContain(plaintextToken);
        assertThat(rawConfig).doesNotContain(plaintextWebhookSecret);
        // Non-secret keys stay in plaintext (JSONB::text renders with a space after the colon).
        assertThat(rawConfig).contains("\"org\": \"acme\"");
    }

    @Test
    void update_withRotatedTokenNeverStoresPlaintextInRawColumn() {
        UUID tenantId = UUID.randomUUID();
        lenient().when(planLimitClient.fetchLimits(tenantId)).thenReturn(Optional.empty());

        ScmConnection created = service.create(tenantId, new ScmConnectionRequest(
                "github", "{\"org\":\"acme\",\"token\":\"ghp_original\"}", null, null, null));

        String rotatedToken = "ghp_rotated-value-67890";
        service.update(tenantId, created.id(), new ScmConnectionRequest(
                null, "{\"org\":\"acme\",\"token\":\"%s\"}".formatted(rotatedToken), null, null, null));

        String rawConfig = jdbc.queryForObject(
                "SELECT config::text FROM scm_connections WHERE id = :id",
                new MapSqlParameterSource("id", created.id()), String.class);

        assertThat(rawConfig).doesNotContain(rotatedToken);
        assertThat(rawConfig).doesNotContain("ghp_original");
    }
}
