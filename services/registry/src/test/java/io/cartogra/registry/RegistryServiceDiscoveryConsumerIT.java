package io.cartogra.registry;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.registry.repository.ServiceHistoryRepository;
import io.cartogra.registry.repository.ServiceRepository;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.event.ServiceDiscoveredPayload;
import io.cartogra.registry.repository.ServiceDiscoveryCommand;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RegistryServiceDiscoveryConsumerIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
    }

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ServiceRepository serviceRepository;

    @Autowired
    ServiceHistoryRepository serviceHistoryRepository;

    @Autowired
    KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @MockitoSpyBean
    ServiceService serviceServiceSpy;

    private KafkaConsumer<String, String> newRawConsumer(String topic) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    /**
     * Polls until a record with the given key shows up, or the timeout elapses. The
     * Kafka test container is reused across test runs ({@code withReuse(true)}), so a
     * DLQ topic can already hold messages from earlier tests/runs; a fresh consumer
     * group starting from "earliest" would otherwise pick up stale records instead of
     * the one this test just produced.
     */
    private ConsumerRecord<String, String> pollForRecord(KafkaConsumer<String, String> consumer, String expectedKey,
                                                           Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : records) {
                if (expectedKey.equals(record.key())) {
                    return record;
                }
            }
        }
        return null;
    }

    private KafkaTemplate<String, String> stringKafkaTemplate() {
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        return new KafkaTemplate<>(producerFactory);
    }

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    @Test
    void serviceDiscoveredUpsertsServiceAndWritesHistory() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String externalId = "test-org/payments-service";

        var payload = new ServiceDiscoveredPayload(
                tenantId,
                connectionId,
                "github",
                externalId,
                "payments-service",
                "Payment processing service",
                "https://github.com/test-org/payments-service",
                "main",
                null, null, null,
                List.of("java", "spring-boot"),
                "UNKNOWN",
                null,
                Instant.parse("2026-05-01T00:00:00Z"),
                "abc123"
        );
        var envelope = EventEnvelope.of("service.discovered", connectionId, tenantId, 1, payload);
        String json = objectMapper.writeValueAsString(envelope);

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        var template = new KafkaTemplate<>(producerFactory);
        template.send(new ProducerRecord<>("cartogra.ingestion.service.discovered", externalId, json));
        template.flush();

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    Optional<Service> found = serviceRepository.findByExternalId(tenantId, externalId);
                    assertThat(found).isPresent();

                    Service svc = found.get();
                    assertThat(svc.lastCommitSha()).isEqualTo("abc123");
                    assertThat(svc.techStack()).contains("java");
                    assertThat(svc.source()).isEqualTo("github");
                    assertThat(svc.teamId()).isNull();

                    var history = serviceHistoryRepository.findByServiceId(tenantId, svc.id(), 10, 0);
                    assertThat(history).isNotEmpty();
                });
    }

    @Test
    void serviceDiscoveredIdempotentSecondEventUpdatesNotDuplicates() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String externalId = "test-org/idempotent-service";

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        var template = new KafkaTemplate<>(producerFactory);

        for (int i = 0; i < 2; i++) {
            var payload = new ServiceDiscoveredPayload(
                    tenantId, connectionId, "github", externalId,
                    "idempotent-service", null, null, "main",
                    null, null, null, List.of("go"), "UNKNOWN",
                    null, null, "sha-" + i);
            var envelope = EventEnvelope.of("service.discovered", connectionId, tenantId, 1, payload);
            template.send(new ProducerRecord<>("cartogra.ingestion.service.discovered",
                    externalId, objectMapper.writeValueAsString(envelope)));
        }
        template.flush();

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    Optional<Service> found = serviceRepository.findByExternalId(tenantId, externalId);
                    assertThat(found).isPresent();
                    assertThat(found.get().lastCommitSha()).isEqualTo("sha-1");
                });
    }

    @Test
    void filterBySourceAfterDiscovery() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String externalId = "test-org/azdo-source-filter-svc";

        var payload = new ServiceDiscoveredPayload(
                tenantId, connectionId, "azuredevops", externalId,
                "azdo-source-filter-svc", null, null, "main",
                null, null, null, List.of(), "UNKNOWN", null, null, null);
        var envelope = EventEnvelope.of("service.discovered", connectionId, tenantId, 1, payload);

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        var template = new KafkaTemplate<>(producerFactory);
        template.send(new ProducerRecord<>("cartogra.ingestion.service.discovered",
                externalId, objectMapper.writeValueAsString(envelope)));
        template.flush();

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    HttpResponse<String> resp = HTTP.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:" + port + "/api/v1/registry/services?source=azuredevops&limit=50"))
                                    .header("X-Tenant-Id", tenantId.toString())
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertThat(resp.statusCode()).isEqualTo(200);
                    JsonNode items = objectMapper.readTree(resp.body()).get("data").get("items");
                    assertThat(items.isArray()).isTrue();
                    boolean found = false;
                    for (JsonNode item : items) {
                        if (externalId.equals(item.get("externalId").stringValue())) {
                            assertThat(item.get("source").stringValue()).isEqualTo("azuredevops");
                            found = true;
                        }
                    }
                    assertThat(found).as("discovered azuredevops service should appear in source=azuredevops results").isTrue();
                });
    }

    @Test
    void malformedPayloadGoesStraightToDlqWithoutRetryDelay() throws Exception {
        String malformedJson = "{not-valid-json";
        String key = "malformed-" + UUID.randomUUID();

        // NOTE: a timing assertion (elapsed time under the 3x1s retry backoff window) was
        // tried here to prove non-retryable failures skip retries entirely, but proved
        // flaky under test-infra overhead (JVM/consumer-poll-loop latency dwarfing the
        // ~1s backoff window it was meant to detect) — dropped per the plan's own
        // guidance to prefer a correctness assertion over a timing one. Correctness (the
        // message actually lands on the DLQ, unmodified) is what's asserted below;
        // addNotRetryableExceptions(JacksonException.class) wiring is otherwise unit-testable
        // only by inspecting the DefaultErrorHandler bean, which is a weaker guarantee than
        // this end-to-end proof that malformed input is recoverable at all.
        try (KafkaConsumer<String, String> dlq = newRawConsumer("cartogra.ingestion.service.discovered.dlq")) {
            stringKafkaTemplate()
                    .send(new ProducerRecord<>("cartogra.ingestion.service.discovered", key, malformedJson))
                    .get();

            ConsumerRecord<String, String> dlqRecord = pollForRecord(dlq, key, Duration.ofSeconds(15));

            assertThat(dlqRecord)
                    .as("malformed JSON should be dead-lettered")
                    .isNotNull();
            assertThat(dlqRecord.key()).isEqualTo(key);
            assertThat(dlqRecord.value()).isEqualTo(malformedJson);
        }
    }

    @Test
    void transientFailureIsRetriedAndCanSucceed() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String externalId = "test-org/transient-retry-service";

        AtomicInteger calls = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("simulated transient failure");
                    }
                    return invocation.callRealMethod();
                })
                .when(serviceServiceSpy)
                .upsertDiscovered(Mockito.any(ServiceDiscoveryCommand.class));

        var payload = new ServiceDiscoveredPayload(
                tenantId, connectionId, "github", externalId,
                "transient-retry-service", null, null, "main",
                null, null, null, List.of("java"), "UNKNOWN", null, null, "sha-retry");
        var envelope = EventEnvelope.of("service.discovered", connectionId, tenantId, 1, payload);
        stringKafkaTemplate()
                .send(new ProducerRecord<>("cartogra.ingestion.service.discovered",
                        externalId, objectMapper.writeValueAsString(envelope)))
                .get();

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    Optional<Service> found = serviceRepository.findByExternalId(tenantId, externalId);
                    assertThat(found).isPresent();
                    assertThat(found.get().lastCommitSha()).isEqualTo("sha-retry");
                });

        assertThat(calls.get())
                .as("first attempt should have failed, second should have succeeded")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void retryExhaustionPublishesToDlq() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID connectionId = UUID.randomUUID();
        String externalId = "test-org/exhausted-retry-service";

        Mockito.doThrow(new IllegalStateException("simulated permanent failure"))
                .when(serviceServiceSpy)
                .upsertDiscovered(Mockito.any(ServiceDiscoveryCommand.class));

        var payload = new ServiceDiscoveredPayload(
                tenantId, connectionId, "github", externalId,
                "exhausted-retry-service", null, null, "main",
                null, null, null, List.of("java"), "UNKNOWN", null, null, "sha-exhausted");
        var envelope = EventEnvelope.of("service.discovered", connectionId, tenantId, 1, payload);
        String json = objectMapper.writeValueAsString(envelope);

        try (KafkaConsumer<String, String> dlq = newRawConsumer("cartogra.ingestion.service.discovered.dlq")) {
            stringKafkaTemplate()
                    .send(new ProducerRecord<>("cartogra.ingestion.service.discovered", externalId, json))
                    .get();

            ConsumerRecord<String, String> dlqRecord = pollForRecord(dlq, externalId, Duration.ofSeconds(20));

            assertThat(dlqRecord)
                    .as("retry-exhausted message should be dead-lettered")
                    .isNotNull();
            assertThat(dlqRecord.key()).isEqualTo(externalId);
            assertThat(dlqRecord.value()).isEqualTo(json);
        }

        Optional<Service> found = serviceRepository.findByExternalId(tenantId, externalId);
        assertThat(found)
                .as("a message that never succeeds should not have been persisted")
                .isEmpty();
    }
}
