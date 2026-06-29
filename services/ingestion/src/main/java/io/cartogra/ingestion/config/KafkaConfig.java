package io.cartogra.ingestion.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean
    @SuppressWarnings("unchecked")
    public ProducerFactory<Object, Object> producerFactory(
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            ObjectMapper objectMapper) {
        var props = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers
        );
        // StringSerializer produces String keys; cast is safe because all producers use String keys.
        Serializer<Object> keySerializer = (Serializer<Object>) (Serializer<?>) new StringSerializer();
        return new DefaultKafkaProducerFactory<>(
                props, keySerializer, new JacksonJsonSerializer<>((JsonMapper) objectMapper));
    }

    @Bean
    public KafkaTemplate<Object, Object> kafkaTemplate(ProducerFactory<Object, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
