package io.cartogra.ingestion.infrastructure.scheduled;

import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the scheduler tick against a real Postgres advisory lock and embedded Kafka:
 * a due scheduled connection publishes exactly one sync command and advances its sync window;
 * non-scheduled and not-yet-due connections are left alone. The automatic tick is parked
 * (PT999H) so the test drives {@code tick()} explicitly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {"cartogra.registry.sync.command"}
)
class SyncSchedulerIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.flyway.schemas", () -> "ingestion");
        registry.add("spring.flyway.default-schema", () -> "ingestion");
        registry.add("ingestion.workers.k8s.enabled", () -> "false");
        registry.add("ingestion.sync.poll-interval", () -> "PT999H");
    }

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    SyncScheduler scheduler;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Test
    void dueScheduledConnectionPublishesAndAdvancesWindow() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = seedConnection(tenantId, true, Instant.now().minus(1, ChronoUnit.HOURS));

        scheduler.tick();

        assertThat(keysOnTopic()).containsOnlyOnce(connectionId.toString());
        assertThat(nextSyncAt(connectionId)).isAfter(Instant.now());
    }

    @Test
    void nonScheduledConnectionIsNotPublished() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = seedConnection(tenantId, false, Instant.now().minus(1, ChronoUnit.HOURS));

        scheduler.tick();

        assertThat(keysOnTopic()).doesNotContain(connectionId.toString());
    }

    @Test
    void notYetDueConnectionIsNotPublished() {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = seedConnection(tenantId, true, Instant.now().plus(1, ChronoUnit.HOURS));

        scheduler.tick();

        assertThat(keysOnTopic()).doesNotContain(connectionId.toString());
    }

    private UUID seedConnection(UUID tenantId, boolean scheduled, Instant nextSyncAt) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO scm_connections (id, tenant_id, provider, config, sync_scheduler,
                    poll_interval_minutes, next_sync_at, created_at, updated_at)
                VALUES (:id, :tenantId, 'github', CAST(:config AS jsonb), :scheduled,
                    15, :nextSyncAt, :now, :now)
                """, new MapSqlParameterSource(Map.of(
                "id", id,
                "tenantId", tenantId,
                "config", "{\"token\":\"t\",\"org\":\"test-org\"}",
                "scheduled", scheduled,
                "nextSyncAt", Timestamp.from(nextSyncAt),
                "now", Timestamp.from(now))));
        return id;
    }

    private Instant nextSyncAt(UUID id) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT next_sync_at FROM scm_connections WHERE id = :id",
                Map.of("id", id), Timestamp.class);
        return ts != null ? ts.toInstant() : null;
    }

    /**
     * Drains the shared topic for a fixed window and returns the message keys. The embedded broker
     * is shared across test methods, so assertions key off this test's connection id rather than
     * global emptiness.
     */
    private List<String> keysOnTopic() {
        List<String> keys = new ArrayList<>();
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        ))) {
            consumer.subscribe(List.of("cartogra.registry.sync.command"));
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> keys.add(r.key()));
            }
        }
        return keys;
    }
}
