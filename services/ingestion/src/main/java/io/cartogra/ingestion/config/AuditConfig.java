package io.cartogra.ingestion.config;

import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.web.audit.KafkaAuditEventAdapter;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

@Configuration
public class AuditConfig {

    @Bean
    public AuditEventPort auditEventPort(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
                                         ObjectMapper objectMapper) {
        var producerFactory = new DefaultKafkaProducerFactory<>(
                Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                new StringSerializer(), new StringSerializer());
        return new KafkaAuditEventAdapter(new KafkaTemplate<>(producerFactory), objectMapper);
    }
}
