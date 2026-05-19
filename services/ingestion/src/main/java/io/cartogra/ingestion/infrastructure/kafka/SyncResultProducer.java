package io.cartogra.ingestion.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.ingestion.application.port.out.SyncResultPayload;
import io.cartogra.ingestion.domain.SyncJob;
import io.opentelemetry.context.Context;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SyncResultProducer {

    private static final String TOPIC = "cartogra.ingestion.sync.completed";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public SyncResultProducer(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishSuccess(SyncJob job, int repositoriesSynced) {
        var payload = new SyncResultPayload(
                job.id(), job.connectionId(), job.tenantId(),
                "COMPLETED", repositoriesSynced, null);
        publish(job, payload);
    }

    public void publishFailure(SyncJob job, String errorMessage) {
        var payload = new SyncResultPayload(
                job.id(), job.connectionId(), job.tenantId(),
                "FAILED", 0, errorMessage);
        publish(job, payload);
    }

    private void publish(SyncJob job, SyncResultPayload payload) {
        var envelope = EventEnvelope.of(
                "sync.completed", job.connectionId(), job.tenantId(), 1, payload);
        var record = new ProducerRecord<Object, Object>(
                TOPIC, job.connectionId().toString(), envelope);

        org.apache.kafka.common.header.Headers kafkaHeaders = record.headers();
        W3CTraceContextPropagator.getInstance().inject(Context.current(), kafkaHeaders,
                (h, key, value) -> h.add(key, value.getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record);
    }
}
