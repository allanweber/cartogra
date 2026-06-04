package io.cartogra.registry;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.application.repository.ServiceHistoryRepository;
import io.cartogra.registry.application.repository.ServiceRepository;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.event.ServiceDiscoveredPayload;
import io.cartogra.test.PostgresTestSupport;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
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
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EmbeddedKafka(
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers",
        topics = {
                "cartogra.ingestion.service.discovered",
                "cartogra.ingestion.ownership.resolved",
                "cartogra.registry.service.registered",
                "cartogra.registry.service.updated",
                "cartogra.registry.service.deleted"
        }
)
class RegistryServiceDiscoveryConsumerIT {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> PostgresTestSupport.POSTGRES.getJdbcUrl() + "&currentSchema=registry");
        registry.add("spring.datasource.username", PostgresTestSupport.POSTGRES::getUsername);
        registry.add("spring.datasource.password", PostgresTestSupport.POSTGRES::getPassword);
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

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

    @Test
    void serviceDiscovered_upsertsServiceAndWritesHistory() throws Exception {
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
    void serviceDiscovered_idempotent_secondEventUpdatesNotDuplicates() throws Exception {
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
}
