package io.cartogra.ingestion.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.ingestion.application.port.out.OwnershipMap;
import io.cartogra.ingestion.application.port.out.OwnershipResolvedPayload;
import io.cartogra.ingestion.application.port.out.ScmRepository;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class OwnershipResolvedProducer {

    private static final String TOPIC = "cartogra.ingestion.ownership.resolved";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public OwnershipResolvedProducer(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(UUID tenantId, UUID connectionId, ScmRepository repo, OwnershipMap ownership) {
        var payload = new OwnershipResolvedPayload(
                tenantId, connectionId, repo.fullPath(),
                ownership.ownerTeams(), ownership.pathOwners());
        var envelope = EventEnvelope.of("ownership.resolved", connectionId, tenantId, 1, payload);
        var record = new ProducerRecord<Object, Object>(TOPIC, repo.fullPath(), envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, key, value) -> h.add(key, value.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
    }
}
