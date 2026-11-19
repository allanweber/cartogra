package io.cartogra.ingestion.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.ingestion.domain.ServiceDiscoveredPayload;
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
public class ServiceDiscoveredProducer {

    private static final Logger log = LoggerFactory.getLogger(ServiceDiscoveredProducer.class);
    private static final String TOPIC = "cartogra.ingestion.service.discovered";

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public ServiceDiscoveredProducer(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(ServiceDiscoveredPayload payload) {
        UUID entityId = payload.connectionId() != null ? payload.connectionId() : payload.tenantId();
        var envelope = EventEnvelope.of(
                "service.discovered",
                entityId,
                payload.tenantId(),
                1,
                payload);
        // K8s no longer sets externalId; use cluster/namespace/name as the stable partition key.
        String messageKey = payload.externalId() != null
                ? payload.externalId()
                : payload.k8sCluster() + "/" + payload.k8sNamespace() + "/" + payload.name();
        var record = new ProducerRecord<Object, Object>(TOPIC, messageKey, envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, key, value) -> h.add(key, value.getBytes(StandardCharsets.UTF_8)));

        log.info("Publishing service.discovered topic={} key={} source={} tenant={}",
                TOPIC, messageKey, payload.source(), payload.tenantId());

        kafkaTemplate.send(record).whenComplete((result, ex) -> {
            if (ex != null) {
                log.warn("Failed to publish service.discovered key={} source={} tenant={}: {}",
                        messageKey, payload.source(), payload.tenantId(), ex.getMessage());
            } else {
                log.debug("service.discovered ack key={} partition={} offset={}",
                        messageKey,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
