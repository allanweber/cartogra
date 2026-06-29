package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.Service;
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
        publish(TOPIC_REGISTERED, "service.registered", service);
    }

    public void publishUpdated(Service service) {
        publish(TOPIC_UPDATED, "service.updated", service);
    }

    public void publishDeleted(Service service, Instant deletedAt) {
        Service deleted = new Service(
                service.id(), service.tenantId(), service.name(), service.description(),
                service.teamId(), service.repositoryUrl(), service.techStack(), service.metadata(),
                service.healthStatus(), service.lastDeployedAt(), service.createdAt(),
                service.updatedAt(), deletedAt,
                service.externalId(), service.connectionId(), service.source(), service.repositoryRef(),
                service.k8sCluster(), service.k8sNamespace(), service.k8sDeployment(),
                service.healthEndpoint(), service.lastCommitAt(), service.lastCommitSha(),
                service.healthCheckedAt());
        publish(TOPIC_DELETED, "service.deleted", deleted);
    }

    private void publish(String topic, String eventType, Service service) {
        var envelope = EventEnvelope.of(eventType, service.id(), service.tenantId(), 1, service);
        var record = new ProducerRecord<String, Object>(topic, service.id().toString(), envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, k, v) -> h.add(k, v.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record);
    }
}
