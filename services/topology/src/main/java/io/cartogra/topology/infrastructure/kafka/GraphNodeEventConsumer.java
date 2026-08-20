package io.cartogra.topology.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.topology.domain.GraphNodeService;
import io.cartogra.topology.domain.event.ServiceLifecyclePayload;
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
 * Projects Registry's service lifecycle into {@code graph_nodes} (CONTEXT.md "Graph nodes
 * projected from the registry"). One listener across all three topics — the same payload
 * shape (the full Registry {@code Service} record) arrives on each; {@code eventType}
 * decides upsert vs. soft-delete. Registry keys every record by service ID, so per-service
 * ordering (registered before updated before deleted) is guaranteed by partitioning.
 */
@Component
public class GraphNodeEventConsumer {

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

    private static final Logger log = LoggerFactory.getLogger(GraphNodeEventConsumer.class);

    private final GraphNodeService graphNodeService;
    private final ObjectMapper objectMapper;

    public GraphNodeEventConsumer(GraphNodeService graphNodeService, ObjectMapper objectMapper) {
        this.graphNodeService = graphNodeService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {
                    "cartogra.registry.service.registered",
                    "cartogra.registry.service.updated",
                    "cartogra.registry.service.deleted"
            },
            groupId = "${spring.kafka.consumer.group-id:topology-consumer}"
    )
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("Received {} key={} partition={} offset={}",
                record.topic(), record.key(), record.partition(), record.offset());

        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<ServiceLifecyclePayload> envelope = objectMapper.readValue(
                    record.value(),
                    new TypeReference<EventEnvelope<ServiceLifecyclePayload>>() {});
            graphNodeService.applyLifecycleEvent(envelope);
            log.info("Applied {} eventId={} serviceId={} tenant={}",
                    envelope.eventType(), envelope.eventId(), envelope.payload().id(), envelope.tenantId());
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to process {} key={} partition={} offset={}: {}",
                    record.topic(), record.key(), record.partition(), record.offset(), e.getMessage(), e);
            throw e;
        }
    }
}
