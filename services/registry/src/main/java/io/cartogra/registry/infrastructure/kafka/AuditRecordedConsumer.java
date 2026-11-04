package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.audit.AuditEvent;
import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.AuditEventService;
import io.cartogra.web.audit.KafkaAuditEventAdapter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.stream.StreamSupport;

@Component
public class AuditRecordedConsumer {

    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            return () -> StreamSupport.stream(carrier.spliterator(), false)
                    .map(Header::key).iterator();
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null) return null;
            Header h = carrier.lastHeader(key);
            return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
        }
    };

    private static final Logger log = LoggerFactory.getLogger(AuditRecordedConsumer.class);

    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    public AuditRecordedConsumer(AuditEventService auditEventService, ObjectMapper objectMapper) {
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaAuditEventAdapter.TOPIC, groupId = "registry-audit-consumer")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<AuditEvent> envelope = objectMapper.readValue(
                    record.value(), new TypeReference<EventEnvelope<AuditEvent>>() {});
            auditEventService.recordPublished(envelope.payload());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process audit.recorded event key={}: {}", record.key(), e.getMessage(), e);
            throw e;
        }
    }
}
