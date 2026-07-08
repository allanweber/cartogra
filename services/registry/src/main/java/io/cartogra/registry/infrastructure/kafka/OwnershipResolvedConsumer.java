package io.cartogra.registry.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.registry.domain.ServiceService;
import io.cartogra.registry.domain.event.OwnershipResolvedPayload;
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
public class OwnershipResolvedConsumer {

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

    private static final Logger log = LoggerFactory.getLogger(OwnershipResolvedConsumer.class);

    private final ServiceService serviceService;
    private final ObjectMapper objectMapper;

    public OwnershipResolvedConsumer(ServiceService serviceService, ObjectMapper objectMapper) {
        this.serviceService = serviceService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "cartogra.ingestion.ownership.resolved", groupId = "${spring.kafka.consumer.group-id:registry-ownership-consumer}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<OwnershipResolvedPayload> envelope = objectMapper.readValue(
                    record.value(),
                    new TypeReference<EventEnvelope<OwnershipResolvedPayload>>() {});
            serviceService.resolveOwnership(envelope.payload());
        } catch (Exception e) {
            log.error("Failed to process ownership.resolved event key={}: {}", record.key(), e.getMessage(), e);
        } finally {
            ack.acknowledge();
        }
    }
}
