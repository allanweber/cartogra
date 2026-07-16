package io.cartogra.registry;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal sibling to {@link RegistryServiceDiscoveryConsumerIT}: proves the same
 * container-level error handler (bounded retry + dead-letter) is wired into
 * {@code OwnershipResolvedConsumer}'s listener container too, not just the
 * service-discovery one. Only the non-retryable (malformed payload) case is covered
 * here — the retry/exhaustion mechanics are already exercised in depth for the
 * sibling consumer and share the exact same container-level error handler bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OwnershipResolvedConsumerIT {

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
    KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;

    @BeforeEach
    void waitForConsumerAssignment() throws InterruptedException {
        for (MessageListenerContainer container : kafkaListenerEndpointRegistry.getListenerContainers()) {
            ContainerTestUtils.waitForAssignment(container, 1);
        }
    }

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
     * DLQ topic can already hold messages from earlier runs; a fresh consumer group
     * starting from "earliest" would otherwise pick up a stale record instead of the
     * one this test just produced.
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

    @Test
    void malformedPayloadGoesStraightToDlq() throws Exception {
        String malformedJson = "{not-valid-json";
        String key = "malformed-ownership-" + UUID.randomUUID();

        var producerFactory = new DefaultKafkaProducerFactory<String, String>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        var template = new KafkaTemplate<>(producerFactory);

        try (KafkaConsumer<String, String> dlq = newRawConsumer("cartogra.ingestion.ownership.resolved.dlq")) {
            template.send(new ProducerRecord<>("cartogra.ingestion.ownership.resolved", key, malformedJson)).get();

            ConsumerRecord<String, String> dlqRecord = pollForRecord(dlq, key, Duration.ofSeconds(15));

            assertThat(dlqRecord)
                    .as("malformed ownership.resolved payload should be dead-lettered")
                    .isNotNull();
            assertThat(dlqRecord.key()).isEqualTo(key);
            assertThat(dlqRecord.value()).isEqualTo(malformedJson);
        }
    }
}
