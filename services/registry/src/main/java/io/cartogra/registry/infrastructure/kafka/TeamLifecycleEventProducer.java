package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.Team;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class TeamLifecycleEventProducer {

    private static final String TOPIC = "cartogra.registry.team.changed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public TeamLifecycleEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishCreated(Team team) {
        publish("team.created", team);
    }

    public void publishUpdated(Team team) {
        publish("team.updated", team);
    }

    public void publishDeleted(Team team) {
        publish("team.deleted", team);
    }

    private void publish(String eventType, Team team) {
        var envelope = EventEnvelope.of(eventType, team.id(), team.tenantId(), 1, team);
        var record = new ProducerRecord<String, Object>(TOPIC, team.id().toString(), envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, k, v) -> h.add(k, v.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
    }
}
