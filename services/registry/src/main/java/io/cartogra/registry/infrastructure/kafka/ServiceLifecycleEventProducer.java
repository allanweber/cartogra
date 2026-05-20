package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.Service;
import io.cartogra.registry.domain.event.ServiceDeletedPayload;
import io.cartogra.registry.domain.event.ServiceRegisteredPayload;
import io.cartogra.registry.domain.event.ServiceUpdatedPayload;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
public class ServiceLifecycleEventProducer {

    private static final String TOPIC_REGISTERED = "cartogra.registry.service.registered";
    private static final String TOPIC_UPDATED    = "cartogra.registry.service.updated";
    private static final String TOPIC_DELETED    = "cartogra.registry.service.deleted";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public ServiceLifecycleEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishRegistered(Service service) {
        var payload = new ServiceRegisteredPayload(
                service.id(), service.tenantId(), service.name(), service.description(),
                service.teamId(), service.repositoryUrl(), service.techStack(),
                service.healthStatus().name());
        publish(TOPIC_REGISTERED, "service.registered", service.id().toString(),
                service.id(), service.tenantId(), payload);
    }

    public void publishUpdated(Service service) {
        var payload = new ServiceUpdatedPayload(
                service.id(), service.tenantId(), service.name(), service.description(),
                service.teamId(), service.repositoryUrl(), service.techStack(),
                service.healthStatus().name());
        publish(TOPIC_UPDATED, "service.updated", service.id().toString(),
                service.id(), service.tenantId(), payload);
    }

    public void publishDeleted(Service service, Instant deletedAt) {
        var payload = new ServiceDeletedPayload(
                service.id(), service.tenantId(), service.name(), deletedAt);
        publish(TOPIC_DELETED, "service.deleted", service.id().toString(),
                service.id(), service.tenantId(), payload);
    }

    private <P> void publish(String topic, String eventType, String key,
                             java.util.UUID entityId, java.util.UUID tenantId, P payload) {
        var envelope = EventEnvelope.of(eventType, entityId, tenantId, 1, payload);
        var record = new ProducerRecord<String, Object>(topic, key, envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, k, v) -> h.add(k, v.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
    }
}
