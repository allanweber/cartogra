package io.cartogra.topology.config;

import io.cartogra.common.audit.AuditEventPort;
import io.cartogra.web.audit.KafkaAuditEventAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AuditConfig {

    @Bean
    public AuditEventPort auditEventPort(ProducerFactory<String, String> dlqProducerFactory, ObjectMapper objectMapper) {
        return new KafkaAuditEventAdapter(new KafkaTemplate<>(dlqProducerFactory), objectMapper);
    }
}
