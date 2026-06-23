package io.cartogra.ingestion.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.common.event.SyncCommandPayload;
import io.cartogra.ingestion.domain.ScmConnection;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class SyncCommandProducer {

    private static final Logger log = LoggerFactory.getLogger(SyncCommandProducer.class);
    private static final String TOPIC = "cartogra.registry.sync.command";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public SyncCommandProducer(KafkaTemplate<Object, Object> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(ScmConnection connection) {
        Map<String, Object> config;
        try {
            config = objectMapper.readValue(connection.config(), MAP_TYPE);
        } catch (Exception ex) {
            log.error("Failed to parse SCM connection config for connection={}: {}",
                    connection.id(), ex.getMessage());
            return;
        }

        var payload = new SyncCommandPayload(
                connection.id(),
                connection.tenantId(),
                connection.provider(),
                config
        );
        var envelope = EventEnvelope.of("sync.command", connection.id(), connection.tenantId(), 1, payload);
        var record = new ProducerRecord<Object, Object>(TOPIC, connection.id().toString(), envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, k, v) -> h.add(k, v.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
        log.info("Published sync command for connection={} provider={}", connection.id(), connection.provider());
    }
}
