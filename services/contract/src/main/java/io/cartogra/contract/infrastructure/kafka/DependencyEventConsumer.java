package io.cartogra.contract.infrastructure.kafka;

import io.cartogra.common.event.EventEnvelope;
import io.cartogra.contract.domain.ConsumerService;
import io.cartogra.contract.domain.InboundEdge;
import io.cartogra.web.tenant.TenantContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Projects topology's {@code dependency.{declared,observed,removed}} into {@code inbound_edges}. */
@Component
public class DependencyEventConsumer {

    static final String TOPIC = "cartogra.topology.dependency.changed";

    private static final TypeReference<EventEnvelope<InboundEdge>> ENVELOPE = new TypeReference<>() {
    };
    private static final Logger log = LoggerFactory.getLogger(DependencyEventConsumer.class);

    private final ConsumerService consumerService;
    private final ObjectMapper objectMapper;

    public DependencyEventConsumer(ConsumerService consumerService, ObjectMapper objectMapper) {
        this.consumerService = consumerService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(id = "dependencyEventConsumer", topics = TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        Context ctx = W3CTraceContextPropagator.getInstance().extract(
                Context.current(), record.headers(), ServiceEventConsumer.HEADERS_GETTER);

        try (Scope _ = ctx.makeCurrent()) {
            EventEnvelope<InboundEdge> envelope = objectMapper.readValue(record.value(), ENVELOPE);
            boolean applied = TenantContext.callAs(envelope.tenantId(),
                    () -> consumerService.apply(envelope.tenantId(), envelope.eventType(), envelope.payload()));
            if (applied) {
                log.info("Applied {} eventId={} edgeId={} tenant={}",
                        envelope.eventType(), envelope.eventId(), envelope.entityId(), envelope.tenantId());
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.warn("Failed to process {} key={} partition={} offset={}: {}",
                    record.topic(), record.key(), record.partition(), record.offset(), e.getMessage(), e);
            throw e;
        }
    }
}
