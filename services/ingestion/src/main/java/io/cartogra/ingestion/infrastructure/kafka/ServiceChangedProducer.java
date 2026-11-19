package io.cartogra.ingestion.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.ingestion.domain.ServiceDiscoveredPayload;
import io.cartogra.ingestion.domain.ServiceRemovedPayload;
import io.cartogra.ingestion.domain.ServiceResyncedPayload;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class ServiceChangedProducer {

    private static final Logger log = LoggerFactory.getLogger(ServiceChangedProducer.class);
    public static final String TOPIC = "cartogra.ingestion.service.changed";
    public static final String SERVICE_DISCOVERED = "service.discovered";
    public static final String SERVICE_REMOVED = "service.removed";
    public static final String SERVICE_RESYNCED = "service.resynced";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public ServiceChangedProducer(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishDiscovered(ServiceDiscoveredPayload payload) {
        UUID entityId = payload.connectionId() != null ? payload.connectionId() : payload.tenantId();
        String messageKey = payload.externalId() != null
                ? payload.externalId()
                : k8sKey(payload.k8sCluster(), payload.k8sNamespace(), payload.name());
        send(SERVICE_DISCOVERED, entityId, payload.tenantId(), messageKey, payload);
    }

    public void publishRemoved(ServiceRemovedPayload payload) {
        send(SERVICE_REMOVED, payload.tenantId(), payload.tenantId(),
                k8sKey(payload.k8sCluster(), payload.k8sNamespace(), payload.name()), payload);
    }

    public void publishResynced(ServiceResyncedPayload payload) {
        send(SERVICE_RESYNCED, payload.tenantId(), payload.tenantId(), payload.k8sCluster(), payload);
    }

    private static String k8sKey(String cluster, String namespace, String name) {
        return cluster + "/" + namespace + "/" + name;
    }

    private void send(String eventType, UUID entityId, UUID tenantId, String messageKey, Object payload) {
        var envelope = EventEnvelope.of(eventType, entityId, tenantId, 1, payload);
        var record = new ProducerRecord<Object, Object>(TOPIC, messageKey, envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, key, value) -> h.add(key, value.getBytes(StandardCharsets.UTF_8)));

        log.info("Publishing {} topic={} key={} tenant={}", eventType, TOPIC, messageKey, tenantId);

        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Failed to publish {} key={} tenant={}: {}", eventType, messageKey, tenantId, ex.getMessage());
            } else {
                log.debug("{} ack key={} partition={} offset={}", eventType, messageKey,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
