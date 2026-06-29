package io.cartogra.ingestion.infrastructure.scheduled;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.ingestion.repository.SyncJobRepository;
import io.cartogra.ingestion.domain.SyncResultPayload;
import io.cartogra.ingestion.domain.SyncJob;
import io.cartogra.ingestion.infrastructure.kafka.SyncCommandConsumer;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "ingestion.workers.k8s.enabled=false")
class StaleJobReaperIT {

    @MockitoBean
    SyncCommandConsumer syncCommandConsumer;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        PostgresTestSupport.POSTGRES.start();
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=ingestion");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Autowired
    StaleJobReaperScheduler reaper;

    @Autowired
    SyncJobRepository syncJobRepository;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void setupConsumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "reaper-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumer = new KafkaConsumer<>(props,
                new org.apache.kafka.common.serialization.StringDeserializer(),
                new org.apache.kafka.common.serialization.StringDeserializer());
        consumer.subscribe(List.of("cartogra.ingestion.sync.completed"));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    void reap_marksStaleJobFailedAndPublishesEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();

        // Insert a RUNNING job with updated_at = 31 minutes ago (stale)
        SyncJob staleJob = syncJobRepository.save(SyncJob.create(tenantId, connectionId, "github"));
        syncJobRepository.markRunning(staleJob.id());
        setUpdatedAt(staleJob.id(), Instant.now().minus(Duration.ofMinutes(31)));

        // Insert a RUNNING job with updated_at = now (fresh — should not be reaped)
        UUID connectionId2 = UUID.randomUUID();
        SyncJob freshJob = syncJobRepository.save(SyncJob.create(tenantId, connectionId2, "github"));
        syncJobRepository.markRunning(freshJob.id());

        reaper.reap();

        // Stale job must be FAILED
        Optional<SyncJob> reaped = syncJobRepository.findById(tenantId, staleJob.id());
        assertThat(reaped).isPresent();
        assertThat(reaped.get().status()).isEqualTo("FAILED");
        assertThat(reaped.get().errorMessage()).isEqualTo("reaper: timed out");

        // Fresh job must still be RUNNING
        Optional<SyncJob> notReaped = syncJobRepository.findById(tenantId, freshJob.id());
        assertThat(notReaped).isPresent();
        assertThat(notReaped.get().status()).isEqualTo("RUNNING");

        // Exactly one Kafka message for the stale job (producer keys by connectionId)
        ConsumerRecord<String, String> record = pollForRecord(staleJob.connectionId().toString());
        assertThat(record).isNotNull();
        EventEnvelope<SyncResultPayload> envelope = objectMapper.readValue(
                record.value(),
                new TypeReference<EventEnvelope<SyncResultPayload>>() {});
        assertThat(envelope.payload().status()).isEqualTo("FAILED");
        assertThat(envelope.tenantId()).isEqualTo(tenantId);
    }

    private void setUpdatedAt(UUID jobId, Instant updatedAt) {
        jdbc.update(
                "UPDATE sync_jobs SET updated_at = :updatedAt WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("updatedAt", Timestamp.from(updatedAt))
                        .addValue("id", jobId));
    }

    private ConsumerRecord<String, String> pollForRecord(String key) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(10).toMillis();
        while (System.currentTimeMillis() < deadline) {
            var records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> record : records) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }
}
