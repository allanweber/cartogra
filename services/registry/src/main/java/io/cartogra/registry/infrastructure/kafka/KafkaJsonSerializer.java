package io.cartogra.registry.infrastructure.kafka;

import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.databind.ObjectMapper;

public class KafkaJsonSerializer<T> implements Serializer<T> {

    private final ObjectMapper objectMapper;

    public KafkaJsonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public byte[] serialize(String topic, T data) {
        if (data == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsBytes(data);
        } catch (Exception e) {
            throw new SerializationException("Failed to serialize Kafka message to JSON for topic " + topic, e);
        }
    }
}
