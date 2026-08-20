package io.cartogra.topology.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.test.KafkaTestSupport;
import io.cartogra.test.PostgresTestSupport;
import io.cartogra.topology.domain.GraphNode;
import io.cartogra.topology.domain.GraphNodeService;
import io.cartogra.topology.domain.event.ServiceLifecyclePayload;
import io.cartogra.topology.repository.GraphNodeRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
class GraphNodeEventConsumerIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=topology");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KafkaTestSupport.KAFKA::getBootstrapServers);
        // A unique group id isolates this context's consumer from every other topology
        // @SpringBootTest context (AbstractTopologyIT's shared context,
        // BackfillInternalControllerIT's own) — all boot the same GraphNodeEventConsumer
        // bean; sharing the hardcoded "topology-consumer" group id would split the three
        // single-partition topics' partitions across whichever contexts are alive at once,
        // and this class's own container could end up with none of them.
        registry.add("spring.kafka.consumer.group-id", () -> "graph-node-consumer-it-" + UUID.randomUUID());
    }

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    GraphNodeRepository graphNodeRepository;

    @Autowired
    KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @MockitoSpyBean
    GraphNodeService graphNodeServiceSpy;

    @MockitoSpyBean
    GraphNodeRepository graphNodeRepositorySpy;

    // 3 — one partition per subscribed topic (registered/updated/deleted), all on the one
    // listener container. ContainerTestUtils.waitForAssignment requires an exact match, not
    // "at least" — passing 1 here throws once all 3 partitions land ("Expected 1 but got 3").
    private static final int SUBSCRIBED_PARTITIONS = 3;

    @BeforeEach
    void waitForConsumerAssignment() {
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, SUBSCRIBED_PARTITIONS);
        }
    }

    private KafkaTemplate<String, String> stringKafkaTemplate() {
        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        return new KafkaTemplate<>(producerFactory);
    }

    private void send(String topic, EventEnvelope<ServiceLifecyclePayload> envelope) {
        String json = objectMapper.writeValueAsString(envelope);
        stringKafkaTemplate().send(new ProducerRecord<>(topic, envelope.payload().id().toString(), json));
        stringKafkaTemplate().flush();
    }

    @Test
    void registeredEventCreatesGraphNode() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        var payload = new ServiceLifecyclePayload(serviceId, tenantId, "payments", null, "STANDARD", "HEALTHY", null);
        send("cartogra.registry.service.registered", EventEnvelope.of("service.registered", serviceId, tenantId, 1, payload));

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    Optional<GraphNode> found = graphNodeRepository.findByServiceId(tenantId, serviceId);
                    assertThat(found).isPresent();
                    assertThat(found.get().name()).isEqualTo("payments");
                    assertThat(found.get().healthStatus()).isEqualTo("HEALTHY");
                    assertThat(found.get().isDeleted()).isFalse();
                });
    }

    @Test
    void updatedEventRefreshesNodeMetadata() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        var registered = new ServiceLifecyclePayload(serviceId, tenantId, "payments", null, "STANDARD", "HEALTHY", null);
        send("cartogra.registry.service.registered", EventEnvelope.of("service.registered", serviceId, tenantId, 1, registered));

        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .until(() -> graphNodeRepository.findByServiceId(tenantId, serviceId).isPresent());

        UUID team = UUID.randomUUID();
        var updated = new ServiceLifecyclePayload(serviceId, tenantId, "payments-v2", team, "CRITICAL", "DEGRADED", null);
        send("cartogra.registry.service.updated", EventEnvelope.of("service.updated", serviceId, tenantId, 1, updated));

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    Optional<GraphNode> found = graphNodeRepository.findByServiceId(tenantId, serviceId);
                    assertThat(found).isPresent();
                    assertThat(found.get().name()).isEqualTo("payments-v2");
                    assertThat(found.get().teamId()).isEqualTo(team);
                    assertThat(found.get().tier()).isEqualTo("CRITICAL");
                    assertThat(found.get().healthStatus()).isEqualTo("DEGRADED");
                });
    }

    @Test
    void deletedEventSoftDeletesNode() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        var registered = new ServiceLifecyclePayload(serviceId, tenantId, "payments", null, "STANDARD", "HEALTHY", null);
        send("cartogra.registry.service.registered", EventEnvelope.of("service.registered", serviceId, tenantId, 1, registered));

        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(300))
                .until(() -> graphNodeRepository.findByServiceId(tenantId, serviceId).isPresent());

        Instant deletedAt = Instant.parse("2026-08-01T00:00:00Z");
        var deleted = new ServiceLifecyclePayload(serviceId, tenantId, "payments", null, "STANDARD", "HEALTHY", deletedAt);
        send("cartogra.registry.service.deleted", EventEnvelope.of("service.deleted", serviceId, tenantId, 1, deleted));

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    Optional<GraphNode> found = graphNodeRepository.findByServiceId(tenantId, serviceId);
                    assertThat(found).isPresent();
                    assertThat(found.get().isDeleted()).isTrue();
                    assertThat(found.get().deletedAt()).isEqualTo(deletedAt);
                });
    }

    @Test
    void replayingTheSameEnvelopeIsANoOp() {
        UUID tenantId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        var payload = new ServiceLifecyclePayload(serviceId, tenantId, "payments", null, "STANDARD", "HEALTHY", null);
        var envelope = EventEnvelope.of("service.registered", serviceId, tenantId, 1, payload);
        String json = objectMapper.writeValueAsString(envelope);

        // Send the exact same serialized envelope twice — same eventId both times.
        var template = stringKafkaTemplate();
        template.send(new ProducerRecord<>("cartogra.registry.service.registered", serviceId.toString(), json));
        template.send(new ProducerRecord<>("cartogra.registry.service.registered", serviceId.toString(), json));
        template.flush();

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> verify(graphNodeServiceSpy, times(2)).applyLifecycleEvent(org.mockito.ArgumentMatchers.any()));

        // Both deliveries reached the service (proven above); the replay's dedupe check must
        // have short-circuited before mutating the node — upsert() itself only ran once.
        verify(graphNodeRepositorySpy, times(1)).upsert(org.mockito.ArgumentMatchers.any());

        Optional<GraphNode> found = graphNodeRepository.findByServiceId(tenantId, serviceId);
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("payments");
    }
}
