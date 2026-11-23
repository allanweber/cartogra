package io.cartogra.topology.config;

import io.cartogra.topology.infrastructure.kafka.DependencyEventPublisher;
import io.cartogra.web.lock.AdvisoryLockRepository;
import io.cartogra.web.outbox.JdbcOutboxRepository;
import io.cartogra.web.outbox.OutboxRelay;
import io.cartogra.web.outbox.OutboxRepository;
import io.cartogra.web.outbox.OutboxWriter;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {

    @Bean
    public OutboxRepository outboxRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new JdbcOutboxRepository(jdbc, objectMapper);
    }

    @Bean
    public OutboxWriter outboxWriter(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        return new OutboxWriter(outboxRepository, objectMapper);
    }

    /** Idempotent, all-replica acks: a retried send must not duplicate or reorder records of one key. */
    @Bean
    public OutboxRelay outboxRelay(OutboxRepository outboxRepository, AdvisoryLockRepository lockRepository,
            OutboxProperties properties, @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        var props = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) properties.sendTimeout().toMillis());
        var template = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(props, new StringSerializer(), new StringSerializer()));
        return new OutboxRelay(outboxRepository, template, lockRepository, properties.batchSize(),
                properties.sendTimeout());
    }

    @Bean
    public NewTopic dependencyChangedTopic() {
        return TopicBuilder.name(DependencyEventPublisher.TOPIC).build();
    }
}
