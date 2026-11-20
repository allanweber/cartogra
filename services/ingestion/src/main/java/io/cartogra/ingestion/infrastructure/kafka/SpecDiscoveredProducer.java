package io.cartogra.ingestion.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.common.event.UuidV5;
import io.cartogra.ingestion.domain.SpecDiscoveredPayload;
import io.cartogra.ingestion.domain.SpecFile;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class SpecDiscoveredProducer {

    public static final String TOPIC = "cartogra.ingestion.spec.discovered";
    public static final String EVENT_TYPE = "spec.discovered";

    private static final Logger log = LoggerFactory.getLogger(SpecDiscoveredProducer.class);

    private final KafkaTemplate<Object, Object> kafkaTemplate;

    public SpecDiscoveredProducer(KafkaTemplate<Object, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(UUID tenantId, UUID connectionId, String repositoryFullName, SpecFile spec) {
        byte[] content = spec.content().getBytes(StandardCharsets.UTF_8);
        var payload = new SpecDiscoveredPayload(tenantId, connectionId, repositoryFullName, spec.filePath(),
                sha256(content), Base64.getEncoder().encodeToString(content));
        UUID entityId = UuidV5.fromNames(tenantId.toString(), repositoryFullName, spec.filePath());
        var envelope = EventEnvelope.of(EVENT_TYPE, entityId, tenantId, 1, payload);
        var record = new ProducerRecord<Object, Object>(TOPIC, entityId.toString(), envelope);
        W3CTraceContextPropagator.getInstance().inject(Context.current(), record.headers(),
                (h, key, value) -> h.add(key, value.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record).whenComplete((_, ex) -> {
            if (ex != null) {
                log.warn("Failed to publish {} for repo={} path={}: {}",
                        EVENT_TYPE, repositoryFullName, spec.filePath(), ex.getMessage());
            }
        });
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
