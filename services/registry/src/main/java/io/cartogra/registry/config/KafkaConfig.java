package io.cartogra.registry.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, Object> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            ObjectMapper objectMapper) {
        var props = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new DefaultKafkaProducerFactory<>(
                props, new StringSerializer(), new JacksonJsonSerializer<>((JsonMapper) objectMapper));
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /**
     * Dedicated string-valued producer for dead-letter publishing. The consumers
     * this
     * error handler serves receive raw JSON strings
     * ({@code ConsumerRecord<String, String>});
     * publishing the failed record's original value through the JSON-serializing
     * {@link #kafkaTemplate} would re-encode (and mangle) an already-serialized
     * JSON
     * string, so this uses a plain {@link StringSerializer} to republish it
     * byte-for-byte.
     */
    @Bean
    public ProducerFactory<String, String> dlqProducerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        var props = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer());
    }

    @Bean
    public KafkaTemplate<String, String> dlqKafkaTemplate(ProducerFactory<String, String> dlqProducerFactory) {
        return new KafkaTemplate<>(dlqProducerFactory);
    }

    /**
     * Container-level error handler picked up automatically by Spring Boot's
     * autoconfigured {@code ConcurrentKafkaListenerContainerFactory} (a single
     * {@code CommonErrorHandler} bean is wired in via
     * {@code KafkaAnnotationDrivenConfiguration}/{@code ConcurrentKafkaListenerContainerFactoryConfigurer}
     * — confirmed against the resolved spring-boot-kafka 4.0.0 sources). Retries a
     * failed record 3 times, 1 second apart, then publishes it to
     * {@code <source-topic>.dlq}. JSON deserialization failures are not retried —
     * they are not going to fix themselves — and go straight to the DLQ.
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> dlqKafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(dlqKafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlq", record.partition()));
        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        errorHandler.addNotRetryableExceptions(JacksonException.class);
        return errorHandler;
    }
}
