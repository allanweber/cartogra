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

import java.nio.charset.StandardCharsets;

@Component
public class SyncCommandProducer {

    private static final Logger log = LoggerFactory.getLogger(SyncCommandProducer.class);
    private static final String TOPIC = "cartogra.registry.sync.command";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public SyncCommandProducer(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(ScmConnection connection) {
        var payload = new SyncCommandPayload(connection.id(), connection.tenantId(), connection.provider());
        var envelope = EventEnvelope.of("sync.command", connection.id(), connection.tenantId(), 1, payload);
        var record = new ProducerRecord<Object, Object>(TOPIC, connection.id().toString(), envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, k, v) -> h.add(k, v.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
        log.info("Published sync command for connection={} provider={}", connection.id(), connection.provider());
    }
}
