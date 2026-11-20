package io.cartogra.contract.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.contract.domain.SpecDiscoveredPayload;
import io.cartogra.contract.domain.SpecDiscoveryService;
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

/**
 * Applies ingestion's {@code spec.discovered}. An invalid spec is stored as REJECTED and acked; only a record that
 * cannot be read goes to the DLQ of {@code KafkaConfig}.
 */
@Component
public class SpecDiscoveredConsumer {

    static final String TOPIC = "cartogra.ingestion.spec.discovered";
    static final String SPEC_DISCOVERED = "spec.discovered";

    private static final TextMapGetter<Headers> HEADERS_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Headers carrier) {
            return () -> StreamSupport.stream(carrier.spliterator(), false).map(Header::key).iterator();
        }

        @Override
        public String get(Headers carrier, String key) {
            if (carrier == null) return null;
            Header h = carrier.lastHeader(key);
            return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
        }
    };

    private static final Logger log = LoggerFactory.getLogger(SpecDiscoveredConsumer.class);

    private final SpecDiscoveryService specDiscoveryService;
    private final ObjectMapper objectMapper;

    public SpecDiscoveredConsumer(SpecDiscoveryService specDiscoveryService, ObjectMapper objectMapper) {
        this.specDiscoveryService = specDiscoveryService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "specDiscoveredConsumer", topics = TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<SpecDiscoveredPayload> envelope = objectMapper.readValue(
                    record.value(), new TypeReference<EventEnvelope<SpecDiscoveredPayload>>() {});
            if (!SPEC_DISCOVERED.equals(envelope.eventType())) {
                ack.acknowledge();
                return;
            }
            specDiscoveryService.ingest(envelope.payload());
            log.info("Applied {} eventId={} repo={} tenant={}", envelope.eventType(), envelope.eventId(),
                    envelope.payload().repositoryFullName(), envelope.tenantId());
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to process {} key={} partition={} offset={}: {}",
                    record.topic(), record.key(), record.partition(), record.offset(), e.getMessage(), e);
            throw e;
        }
    }
}
